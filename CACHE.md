# Server Jars Cache (Iran-friendly)

This node keeps a shared cache of big server jars so you only download them ONCE,
even if your connection to Mojang/PaperCDN is slow or blocked.

## Where

- Global cache: `Raspberry/cache/` (created automatically)
- Paper/vanilla version jars: `Raspberry/versions/<software>/<version>.jar` (already cached by SoftwareDownloader)

## What is cached

| File | Used by | Copied to |
|---|---|---|
| `cache/mojang_<version>.jar` | paperclip / fabric / forge bootstrap (vanilla server jar) | `<workspace>/cache/` on every new/restarted server |
| `versions/paper/<v>.jar` | paper server jar | already shared across all workspaces |

## How it works

1. On `create_server` (and every power start), the core copies any matching
   `mojang_<version>.jar` from the global cache into the workspace `cache/` folder.
2. Paperclip finds the vanilla jar there and skips its own download.
3. After a successful boot, newly downloaded `mojang_*.jar` files are promoted
   back into the global cache for the next server.

## Manual warm-up (recommended in Iran)

Download once (from any network/VPN that works) and drop the files here:

```
Raspberry/cache/mojang_1.21.4.jar    # from https://piston-data.mojang.com/... (server.jar)
Raspberry/versions/paper/1.21.4.jar  # from https://fill.papermc.io (already automatic)
```

Any file you place in `cache/` following the `mojang_<version>.jar` naming is
reused forever - no more repeat downloads.
