# Geyser and new Bedrock (pocket edition) versions

## The problem
Phone players on Bedrock **26.60** saw this when joining a Paper 26.3 server:

> Outdated Geyser proxy! This server supports the following Bedrock versions: 26.30 … 26.52

## The answer: update Geyser, don't modify it
The official Geyser has supported Bedrock **26.60** since **21 October 2026** (GeyserMC/Geyser commit `fd35d59`, "Support 26.60").
The latest release supports Bedrock 26.40 to 26.60 and Java 26.3, so no custom version is needed.

## How to update (about 5 minutes)
1. Stop the server.
2. Download the latest **Geyser-Spigot** from <https://geysermc.org/download> (the Spigot build runs on Paper).
   If you use **Floodgate**, update it from the same page.
3. Move the old Geyser `.jar` out of `plugins/` (keep it as a backup) and put the new one in.
   Leave the `plugins/Geyser-Spigot/` folder alone, because your settings live there.
4. Start the server. The console should list 26.60 among the supported Bedrock versions.
5. Join from your phone.

6. **Switch on NetherNet (required for 26.60).** If the console shows *"WARNING! NetherNet support is NOT enabled"*,
   open `plugins/Geyser-Spigot/config.yml`, find `transport: raknet` under `bedrock:` and change it to:
   ```yaml
   transport: nethernet
   ```
   Keep the spaces at the start of the line exactly as they were. Save, restart, then run `geyser nethernet` in the console.
   You should see "Built-in signaling started on ..." and the warning should be gone.
   NetherNet needs the Bedrock port open for **both UDP and TCP**. If phones still can't join, ask your host to open TCP on that port.

**Why step 6:** Bedrock 26.60 removed the old connection method (RakNet) and only uses the new one (NetherNet).
A Geyser set to `raknet` waits for phones on a connection method they no longer have.

**Trade-off:** the new Geyser no longer supports Bedrock 26.30 to 26.34. Bedrock updates itself automatically, so this rarely matters.

## If this happens again
Check <https://geysermc.org/download> first. Usually the Geyser team releases support for a new Bedrock version
within days. Adding support yourself is only a one-line change when the network protocol didn't change. Otherwise it is a big job.

Geyser is MIT-licensed. Credit: [GeyserMC](https://github.com/GeyserMC/Geyser).
