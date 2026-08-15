# Project

- **Loader:** Fabric
- **Language:** Kotlin (`fabric-language-kotlin`)
- **Minecraft Version:** `26.2`
- **Mappings:** Mojang mappings (`mojmap`). MC 26.1 is unobfuscated; **do not use Yarn** for this version.
- **Java:** 25

## Minecraft Dev MCP Server
- 
When you need vanilla Minecraft APIs, class/method signatures, injection targets, registry IDs, or to understand how client/server code works **use the** `user-minecraft-dev` **MCP server**.
- If using the MCP server, always pass `version: "26.2"` and `mapping: "mojmap"` unless a tool does not take those fields.