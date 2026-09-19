-- 阿里百炼 FunAsr 在线语音识别（Lua 脚本插件）
--
-- 职责划分：
--   Lua   = 全部功能逻辑（连接时机、状态机、prebuffer、dashscope 协议组装/解析、结果上报）
--   宿主  = 仅提供通用原语：
--     host.ws         通用 WebSocket 白名单（connect/sendText/sendBinary/close）
--     host.asr.emit*  结果回传桥（final/partial/error/state）
--     host.json / host.config / host.uuid
--   主 App 只把 PCM 数据通过 processAudioChunk 提交给 Lua，由 Lua 决定缓冲还是发送

local plugin = {}

local MODEL = "qwen-audio-3.0-asr-flash-streaming"
local WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
local SAMPLE_RATE = 16000
local FORMAT = "pcm"
local KEY_API_KEY = "apiKey"

local taskId = ""
local audioReady = false
local prebuffer = {}

-- ================= 元信息 =================

function plugin.getProviderId()
    return "funasr"
end

function plugin.getDisplayName()
    return "阿里百炼 FunAsr"
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
    local v = host.config.get(KEY_API_KEY)
    return v ~= nil and v ~= ""
end

function plugin.getSettingsSchema()
    return {
        {
            key = KEY_API_KEY,
            label = "API Key",
            type = "secret",
            placeholder = "输入阿里百炼 API Key",
            helpText = "访问阿里云百炼平台获取 API Key",
        },
    }
end

function plugin.initialize()
    return true
end

-- ================= 启动 =================

function plugin.start()
    local apiKey = host.config.get(KEY_API_KEY)
    if apiKey == nil or apiKey == "" then
        host.asr.emitError("未配置 API Key，请在插件设置中填写")
        return false
    end
    taskId = host.uuid()
    audioReady = false
    prebuffer = {}
    local ok = host.ws.connect(WS_URL, { Authorization = "Bearer " .. apiKey }, {
        onOpen = function() plugin.onWsOpen() end,
        onMessage = function(text) plugin.onWsMessage(text) end,
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
    plugin.sendRunTask()
end

function plugin.onWsMessage(text)
    local msg = host.json.decode(text)
    if msg == nil or msg.header == nil then return end
    local header = msg.header
    local event = header.event

    if event == "task-started" then
        -- 任务就绪：开始直发音频，并冲刷连接建立前缓冲的音频
        audioReady = true
        plugin.flushPrebuffer()
    elseif event == "result-generated" then
        local output = msg.payload and msg.payload.output
        if output == nil then return end
        local sentence = output.sentence
        if sentence == nil or sentence.heartbeat then return end
        local resultText = sentence.text or ""
        if resultText ~= "" then
            if sentence.sentence_end then
                host.asr.emitFinal(resultText)
            else
                host.asr.emitPartial(resultText)
            end
        end
    elseif event == "task-finished" then
        host.ws.close()
    elseif event == "task-failed" then
        local code = header.error_code or "UNKNOWN"
        local reason = header.error_message or "Unknown error"
        host.asr.emitError("识别失败 [" .. code .. "]: " .. reason)
        host.ws.close()
    end
end

function plugin.onWsError(msg)
    host.asr.emitError(msg)
end

function plugin.onWsClose()
    taskId = ""
    audioReady = false
    prebuffer = {}
end

-- ================= 音频数据（主 App 每帧提交，Lua 决策） =================

function plugin.processAudioChunk(pcm)
    if audioReady then
        host.ws.sendBinary(pcm)
    else
        table.insert(prebuffer, pcm)
        if #prebuffer > 300 then table.remove(prebuffer, 1) end
    end
end

function plugin.flushPrebuffer()
    for _, frame in ipairs(prebuffer) do
        host.ws.sendBinary(frame)
    end
    prebuffer = {}
end

-- ================= dashscope 协议 =================

function plugin.sendRunTask()
    if taskId == "" then return end
    host.ws.sendText(host.json.encode({
        header = {
            action = "run-task",
            task_id = taskId,
            streaming = "duplex",
        },
        payload = {
            task_group = "audio",
            task = "asr",
            ["function"] = "recognition",
            model = MODEL,
            parameters = {
                format = FORMAT,
                sample_rate = SAMPLE_RATE,
            },
            input = {},
        },
    }))
end

function plugin.stop()
    if taskId ~= "" then
        host.ws.sendText(host.json.encode({
            header = {
                action = "finish-task",
                task_id = taskId,
                streaming = "duplex",
            },
            payload = { input = {} },
        }))
    end
end

function plugin.cancel()
    host.ws.close()
    taskId = ""
    audioReady = false
    prebuffer = {}
end

function plugin.getState()
    return 0
end

return plugin
