-- ximed 剪贴板同步插件（Lua 脚本插件）
--
-- 对接 ximed 服务器的 HTTP 接口，实现文本双向同步。
--
-- 职责划分：
--   Lua   = 协议逻辑（HTTP 端点拼装、Basic Auth、ETag 条件拉取、Profile JSON 组装/解析）
--   宿主  = 同步引擎（轮询/去重/回声抑制）+ 通用原语：
--     host.http        HTTP 白名单（request/lastError，域名经用户授权）
--     host.json        JSON 编解码
--     host.config      配置存储（含 ETag 缓存）
--     host.crypto      sha256/hex（hash 计算，可选；宿主引擎已算好 hash 传入）
--
-- ximed HTTP 接口（参考 ximed crates/client/src/transport.rs HttpTransport）：
--   端点   {server_url}/api/clipboard
--   push   PUT {endpoint}，Authorization: Basic base64(user:pass)，body = Profile JSON
--   pull   GET {endpoint}，Authorization: Basic；ETag 缓存到 host.config，
--          拉取时发 If-None-Match，304 → 无变更（返回 nil）
--   Profile JSON（snake_case）：
--     { "type":"text", "hash":"...", "text":"...", "has_data":false,
--       "data_name":null, "size":12, "source":null }

local plugin = {}

local KEY_SERVER_URL = "serverUrl"
local KEY_USERNAME = "username"
local KEY_PASSWORD = "password"
local KEY_LAST_ETAG = "lastEtag"

local ENDPOINT_SUFFIX = "/api/clipboard"

-- ================= 工具函数 =================

local function base64encode(str)
    return host.crypto.base64(str)
end

local function basicAuthHeader(username, password)
    local cred = username .. ":" .. password
    return "Basic " .. base64encode(cred)
end

local function endpointUrl()
    local base = host.config.get(KEY_SERVER_URL) or ""
    base = base:gsub("/+$", "")
    if base == "" then return nil end
    return base .. ENDPOINT_SUFFIX
end

local function buildHeaders()
    local headers = {}
    local username = host.config.get(KEY_USERNAME) or ""
    local password = host.config.get(KEY_PASSWORD) or ""
    if username ~= "" then
        headers["Authorization"] = basicAuthHeader(username, password)
    end
    headers["Accept"] = "application/json"
    return headers
end

-- ================= 配置 schema（与 manifest 一致） =================

function plugin.getSettingsSchema()
    return {
        {
            key = KEY_SERVER_URL,
            label = "服务器地址",
            type = "text",
            placeholder = "https://host:port",
            helpText = "ximed 服务器地址（形如 https://192.168.1.50:8080）",
        },
        {
            key = KEY_USERNAME,
            label = "用户名",
            type = "text",
            required = false,
        },
        {
            key = KEY_PASSWORD,
            label = "密码",
            type = "secret",
            required = false,
        },
        {
            key = "testConnection",
            label = "测试连接",
            type = "button",
            action = "testConnection",
            required = false,
        },
    }
end

-- ================= 生命周期 =================

function plugin.onLoad()
    return true
end

function plugin.onUnload()
    return true
end

-- ================= 同步接口 =================

-- 推送本地 profile 到远端（宿主剪贴板变化时调用）
function plugin.push(profile)
    local url = endpointUrl()
    if url == nil then return false end
    local headers = buildHeaders()
    headers["Content-Type"] = "application/json"
    local body = host.json.encode(profile)
    if body == nil then return false end
    local resp = host.http.request("PUT", url, headers, body)
    if resp == nil then return false end
    if resp.status >= 200 and resp.status < 300 then
        -- 记录远端 etag，供下次条件拉取
        local etag = resp.headers["ETag"]
        if etag == nil then etag = resp.headers["etag"] end
        if etag ~= nil then host.config.set(KEY_LAST_ETAG, etag) end
        return true
    end
    return false
end

-- 拉取远端 profile（宿主轮询调用）；无变更返回 nil
function plugin.pull()
    local url = endpointUrl()
    if url == nil then return nil end
    local headers = buildHeaders()
    local etag = host.config.get(KEY_LAST_ETAG)
    if etag ~= nil and etag ~= "" then
        headers["If-None-Match"] = etag
    end
    local resp = host.http.request("GET", url, headers, nil)
    if resp == nil then return nil end
    if resp.status == 304 then
        -- Not Modified，无变更
        return nil
    end
    if resp.status >= 200 and resp.status < 300 then
        local etag = resp.headers["ETag"]
        if etag == nil then etag = resp.headers["etag"] end
        if etag ~= nil then host.config.set(KEY_LAST_ETAG, etag) end
        local decoded = host.json.decode(resp.text)
        if decoded == nil then return nil end
        return decoded
    end
    return nil
end

-- 校验配置可用性（连接测试）；返回错误消息，nil 表示成功
function plugin.testConnection()
    local url = endpointUrl()
    if url == nil then return "未配置服务器地址" end
    local resp = host.http.request("GET", url, buildHeaders(), nil)
    if resp == nil then
        return host.http.lastError() or "连接失败"
    end
    if resp.status == 401 or resp.status == 403 then
        return "认证失败（HTTP " .. resp.status .. "）"
    end
    if resp.status >= 200 and resp.status < 400 then
        return nil
    end
    return "连接失败（HTTP " .. resp.status .. "）"
end

return plugin
