-- 火山引擎（火山方舟）WebSocket 流式语音识别（Lua 脚本插件）
--
-- 职责划分：
--   Lua   = 全部功能逻辑（连接时机、状态机、prebuffer、bigmodel_async 二进制协议组装/解析、结果上报）
--   宿主  = 仅提供通用原语：
--     host.ws         WebSocket 白名单（connect/sendText/sendBinary/close/onBinary）
--     host.zlib       gzip / gunzip（火山二进制帧强制 gzip）
--     host.bin        uint32be / int32be（帧长度与序号字段）
--     host.asr.emit*  结果回传桥
--     host.json / host.config / host.uuid
--
-- 协议（参考官方 sauc_websocket_demo.py）：
--   帧 = 4 字节头 + 4 字节有符号序号 seq + 4 字节大端 payload 长度 + payload
--     头[0] = (协议版本 0x1 << 4) | (头长度单位 0x1)
--     头[1] = (消息类型 << 4) | flags
--     头[2] = (序列化 << 4) | 压缩
--     头[3] = 0
--   消息类型：0x1 full client req / 0x2 audio-only / 0x9 server resp / 0xF error
--   压缩：0x0 none / 0x1 gzip；序列化：0x0 raw / 0x1 json
--   消息类型专属 flags：
--     NO_SEQUENCE=0x0 / POS_SEQUENCE=0x1（带正 seq）/ NEG_SEQUENCE=0x2 / NEG_WITH_SEQUENCE=0x3
--   客户端最后一包：flags=0x3 且 seq 取负
--   服务端响应 flags：0x1 带 seq、0x2 末包、0x4 带 event（在 payload 前）

local plugin = {}

local WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
local SAMPLE_RATE = 16000

local KEY_API_KEY = "apiKey"
local KEY_APP_KEY = "appKey"
local KEY_ACCESS_KEY = "accessKey"
local KEY_RESOURCE_ID = "resourceId"
local DEFAULT_RESOURCE = "volc.seedasr.sauc.duration"

local MSG_FULL_CLIENT_REQ = 0x1
local MSG_AUDIO_ONLY = 0x2
local MSG_SERVER_RESP = 0x9
local MSG_SERVER_ERROR = 0xF

local FLAG_POS_SEQUENCE = 0x1
local FLAG_NEG_WITH_SEQUENCE = 0x3
local FLAG_RESP_IS_LAST = 0x2
local FLAG_RESP_HAS_EVENT = 0x4

local taskId = ""
local audioReady = false
local seq = 1
local prebuffer = {}

-- ================= 元信息 =================

function plugin.getProviderId()
    return "volc"
end

function plugin.getDisplayName()
    return "火山引擎流式语音识别"
end

function plugin.getIcon()
    return { assetName = "icon.png" }
end

function plugin.getCapabilities()
    return {
        inputMode = "streaming",
        supportsPartialResults = true,
        maxRecordDurationMillis = 10 * 60 * 1000,
        requiresNetwork = true,
    }
end

function plugin.isConfigured()
    local apiKey = host.config.get(KEY_API_KEY)
    if apiKey ~= nil and apiKey ~= "" then return true end
    local appKey = host.config.get(KEY_APP_KEY)
    local accessKey = host.config.get(KEY_ACCESS_KEY)
    return appKey ~= nil and appKey ~= "" and accessKey ~= nil and accessKey ~= ""
end

function plugin.getSettingsSchema()
    return {
        {
            key = KEY_API_KEY,
            label = "API Key",
            type = "secret",
            placeholder = "输入火山方舟 API Key",
            helpText = "在火山引擎方舟平台申请开通流式语音识别后获取",
        },
        {
            key = KEY_APP_KEY,
            label = "App Key（旧鉴权）",
            type = "secret",
            required = false,
            placeholder = "旧版 App Key（可选）",
            section = "旧鉴权",
        },
        {
            key = KEY_ACCESS_KEY,
            label = "Access Key（旧鉴权）",
            type = "secret",
            required = false,
            placeholder = "旧版 Access Key（可选）",
            section = "旧鉴权",
        },
        {
            key = KEY_RESOURCE_ID,
            label = "模型资源 ID",
            type = "text",
            defaultValue = DEFAULT_RESOURCE,
            placeholder = DEFAULT_RESOURCE,
            helpText = "默认流式识别 2.0（volc.seedasr.sauc.duration）；1.0 模型填 volc.bigasr.sauc.duration",
        },
    }
end

function plugin.initialize()
    return true
end

-- ================= 二进制协议原语 =================

-- 帧头 + seq(4) + payload_size(4) + payload
local function build_frame(msg_type, seq_value, ser, comp, payload)
    local flags
    if seq_value < 0 then
        flags = FLAG_NEG_WITH_SEQUENCE
    else
        flags = FLAG_POS_SEQUENCE
    end
    return string.char(0x11, msg_type * 16 + flags, ser * 16 + comp, 0)
        .. host.bin.int32be(seq_value)
        .. host.bin.uint32be(#payload)
        .. payload
end

local function read_uint32(s, i)
    local b1, b2, b3, b4 = string.byte(s, i, i + 3)
    return b1 * 16777216 + b2 * 65536 + b3 * 256 + b4
end

local function read_int32(s, i)
    local v = read_uint32(s, i)
    if v >= 2147483648 then v = v - 4294967296 end
    return v
end

local function parse_server_frame(frame)
    if #frame < 8 then return nil end
    local b0 = string.byte(frame, 1)
    local b1 = string.byte(frame, 2)
    local b2 = string.byte(frame, 3)
    local header_size = (b0 % 16) * 4
    local msg_type = math.floor(b1 / 16) % 16
    local flags = b1 % 16
    local ser = math.floor(b2 / 16) % 16
    local comp = b2 % 16
    local offset = header_size + 1

    local parsed = { msgType = msg_type }
    if flags % 2 == 1 then -- flags & 0x01：带 sequence
        parsed.seq = read_int32(frame, offset)
        offset = offset + 4
    end
    if math.floor(flags / 2) % 2 == 1 then -- flags & 0x02：末包
        parsed.isLast = true
    end
    if math.floor(flags / 4) % 2 == 1 then -- flags & 0x04：带 event
        parsed.event = read_int32(frame, offset)
        offset = offset + 4
    end

    if msg_type == MSG_SERVER_RESP then
        if offset + 4 > #frame then return nil end
        local size = read_uint32(frame, offset)
        offset = offset + 4
        local payload = string.sub(frame, offset, offset + size - 1)
        if comp == 1 then payload = host.zlib.gunzip(payload) end
        if payload == nil then return parsed end
        if ser == 1 then
            local obj = host.json.decode(payload)
            if obj ~= nil and obj.result ~= nil then
                parsed.text = obj.result.text or ""
            end
        end
    elseif msg_type == MSG_SERVER_ERROR then
        if offset + 8 > #frame then return parsed end
        parsed.code = read_int32(frame, offset)
        local size = read_uint32(frame, offset + 4)
        local start = offset + 8
        parsed.message = string.sub(frame, start, start + size - 1)
    end
    return parsed
end

local function gzip_or_nil(data)
    local gz = host.zlib.gzip(data)
    if gz == nil then
        host.asr.emitError("gzip 压缩失败")
    end
    return gz
end

-- ================= 启动 =================

function plugin.start()
    if not plugin.isConfigured() then
        host.asr.emitError("未配置 API Key，请在插件设置中填写")
        return false
    end
    taskId = host.uuid()
    audioReady = false
    seq = 1
    prebuffer = {}

    local headers = {}
    local apiKey = host.config.get(KEY_API_KEY)
    if apiKey ~= nil and apiKey ~= "" then
        headers["X-Api-Key"] = apiKey
    else
        headers["X-Api-App-Key"] = host.config.get(KEY_APP_KEY)
        headers["X-Api-Access-Key"] = host.config.get(KEY_ACCESS_KEY)
    end
    headers["X-Api-Resource-Id"] = host.config.get(KEY_RESOURCE_ID) or DEFAULT_RESOURCE
    headers["X-Api-Request-Id"] = taskId
    headers["X-Api-Connect-Id"] = host.uuid()
    headers["X-Api-Sequence"] = "-1"

    local ok = host.ws.connect(WS_URL, headers, {
        onOpen = function() plugin.onWsOpen() end,
        onBinary = function(frame) plugin.onWsBinary(frame) end,
        onError = function(msg) plugin.onWsError(msg) end,
        onClose = function() plugin.onWsClose() end,
    })
    if not ok then
        local reason = host.ws.lastError()
        if reason ~= nil and reason ~= "" then
            host.asr.emitError(reason)
        end
        return false
    end
    return true
end

-- ================= WebSocket 事件（状态机） =================

function plugin.onWsOpen()
    local full = host.json.encode({
        user = { uid = host.config.get(KEY_APP_KEY) or "xime" },
        audio = {
            format = "pcm",
            codec = "raw",
            rate = SAMPLE_RATE,
            bits = 16,
            channel = 1,
        },
        request = {
            model_name = "bigmodel",
            enable_itn = true,
            enable_punc = true,
            enable_ddc = false,
            show_utterances = false,
            enable_nonstream = false,
        },
    })
    local gz = gzip_or_nil(full)
    if gz == nil then
        host.ws.close()
        return
    end
    host.ws.sendBinary(build_frame(MSG_FULL_CLIENT_REQ, seq, 0x1, 0x1, gz))
    seq = seq + 1
    audioReady = true
end

function plugin.onWsBinary(frame)
    local parsed = parse_server_frame(frame)
    if parsed == nil then return end

    if parsed.msgType == MSG_SERVER_RESP then
        local text = parsed.text or ""
        if parsed.isLast then
            host.asr.emitFinal(text)
            host.ws.close()
        elseif text ~= "" then
            host.asr.emitPartial(text)
        end
    elseif parsed.msgType == MSG_SERVER_ERROR then
        host.asr.emitError("ASR 错误 " .. tostring(parsed.code or 0) .. ": " .. (parsed.message or ""))
        host.ws.close()
    end
end

function plugin.onWsError(msg)
    host.asr.emitError(msg)
end

function plugin.onWsClose()
    taskId = ""
    audioReady = false
    seq = 1
    prebuffer = {}
end

-- ================= 音频数据（主 App 每帧提交，Lua 决策） =================

function plugin.processAudioChunk(pcm)
    if audioReady then
        local gz = gzip_or_nil(pcm)
        if gz ~= nil then
            host.ws.sendBinary(build_frame(MSG_AUDIO_ONLY, seq, 0x0, 0x1, gz))
            seq = seq + 1
        end
    else
        table.insert(prebuffer, pcm)
        if #prebuffer > 300 then table.remove(prebuffer, 1) end
    end
end

function plugin.stop()
    if taskId == "" then return end
    for _, frame in ipairs(prebuffer) do
        local gz = gzip_or_nil(frame)
        if gz ~= nil then
            host.ws.sendBinary(build_frame(MSG_AUDIO_ONLY, seq, 0x0, 0x1, gz))
            seq = seq + 1
        end
    end
    prebuffer = {}
    -- 最后一包标记：flags=0x3（NEG_WITH_SEQUENCE）且 seq 取负
    local gz = gzip_or_nil("")
    if gz ~= nil then
        host.ws.sendBinary(build_frame(MSG_AUDIO_ONLY, -seq, 0x0, 0x1, gz))
    end
end

function plugin.cancel()
    host.ws.close()
    taskId = ""
    audioReady = false
    seq = 1
    prebuffer = {}
end

function plugin.getState()
    return 0
end

return plugin
