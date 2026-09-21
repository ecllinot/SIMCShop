# SIMCShop

SIMCShop 是一个用于索引和查询 Minecraft 玩家商店告示牌的项目。

模组会在符合条件的服务器中扫描附近商店告示牌，将物品、价格、数量、玩家名和坐标上传到后端。玩家可以通过网页、游戏内 GUI 或命令行查询商店数据。

网页查询地址：https://shop.whalemc.com

## 游戏内使用

模组提供 GUI 搜索和命令行搜索。

打开 GUI：

```text
/smcshop gui
/simcshop gui
```

也可以在 Minecraft 的“控制”设置里，为 `SIMCShop` 绑定快捷键。

命令行搜索：

```text
/smcshop search 紫水晶碎片
/smcshop sell 紫水晶碎片
/smcshop buy 紫水晶碎片
```

同样支持 `simcshop` 命令别名：

```text
/simcshop search 紫水晶碎片
/simcshop sell 紫水晶碎片
/simcshop buy 紫水晶碎片
```

在聊天命令结果中，港口名称可点击复制。