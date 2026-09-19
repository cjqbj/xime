-- 恶搞兔表情包（Lua 脚本插件）
--
-- 资源在 resources/ 下：
--   resources/icon.webp        插件图标
--   resources/emojis/*.jpg     表情图片（宿主渲染）
-- 宿主渲染图片，Lua 只提供路径（host.resource.path）

local plugin = {}

local CATEGORY = "恶搞兔"

function plugin.getCategories()
    return { CATEGORY }
end

function plugin.getEmojis(category, searchText, topK)
    local files = host.resource.list("emojis") or {}
    local list = {}
    local idx = 0
    for _, f in ipairs(files) do
        local name = f:gsub("%.jpg$", ""):gsub("%.png$", ""):gsub("%.webp$", ""):gsub("%.gif$", "")
        if searchText == "" or string.find(name, searchText, 1, true) then
            table.insert(list, {
                id = "emoji_" .. idx,
                text = name,
                insertText = "[表情" .. name .. "]",
                imageUrl = host.resource.path("emojis/" .. f),
                category = CATEGORY,
            })
            idx = idx + 1
        end
        if #list >= topK then break end
    end
    return list
end

function plugin.getCategoryLayoutConfig(category)
    return { columns = 3, itemHeightDp = 110 }
end

function plugin.getIcon()
    return { assetName = "icon.webp" }
end

return plugin
