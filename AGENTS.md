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

## Gradle invocation policy

Gradle here is slow to start and sometimes hangs during initialisation. Never run `gradlew` bare:

1. Launch it detached with output redirected, e.g.
   `Start-Process -FilePath ".\gradlew.bat" -ArgumentList "compileKotlin","--console=plain" -NoNewWindow -PassThru -RedirectStandardOutput <out> -RedirectStandardError <err>`
   (avoid `-q`; config/task lines are the liveness signal).
2. Poll the redirected output file every ~1 s. If 10 s pass with **zero new bytes** and the process has not exited, kill it (`Stop-Process`) and retry once with the same policy. If the retry also stalls, skip the task and report that verification was skipped instead of blocking.
3. Once output starts flowing, allow a generous completion window (several minutes) before giving up.
4. A clean `compileKotlin` prints little or nothing at the end — empty output with exit code 0 is success.