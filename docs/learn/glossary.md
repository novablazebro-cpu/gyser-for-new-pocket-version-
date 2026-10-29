# Glossary

| Term | Plain meaning | Where it showed up |
|---|---|---|
| Proxy | A translator sitting between two sides. Geyser translates between Bedrock (phone) and Java (Paper). | The "Outdated Geyser proxy" error |
| Protocol version | The version of the "network language" a game version speaks. A translator that doesn't know it turns you away. | Bedrock 26.60 = protocol 2225 in Geyser's `GameProtocol.java` |
| Plugin / .jar | An add-on for the server, packed into one file. Updating it means swapping that file. | `plugins/Geyser-Spigot.jar` |
| Fork | Your own copy of someone else's project that you can change. You have to keep it updated yourself. | Why we did not fork Geyser |
| MIT license | A permissive license: use, change and share freely, but keep the copyright notice. | Geyser's `LICENSE` file |
| Release | A finished, downloadable version of a program. | geysermc.org/download |
| Transport | The method a game uses to connect over the internet. | Geyser's `transport:` setting |
| RakNet | Bedrock's old connection method, removed in 26.60. | `transport: raknet` caused the NetherNet warning |
| NetherNet | Bedrock's new connection method (WebRTC plus "signaling" to find the server). | `transport: nethernet` |
| Config file (YAML) | A plugin's settings file. In YAML, the spaces at the start of a line decide its section. | `plugins/Geyser-Spigot/config.yml` |
| TCP / UDP | Two kinds of network traffic. A port can be open for one and closed for the other. | NetherNet needs both on the Bedrock port |
