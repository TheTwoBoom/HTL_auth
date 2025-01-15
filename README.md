# HTLAuth

Easy Authentication for Minecraft via Microsoft Entra ID


![MIT License](https://img.shields.io/badge/License-AGPL3-green.svg)
![Made with Java](https://img.shields.io/badge/Made_with-Java-orange?style=flat&logo=openjdk)
![Made with Intellij IDEA](https://img.shields.io/badge/Made_with-IntelliJ_IDEA-red?style=flat&logo=intellij-idea)

## Features

- Secure authentication for players via Microsoft OAuth
- Only allows certain e-mail domains to join
- Perfect for School/Work Minecraft Servers

## Installation
Requires Microsoft App Registration via Microsoft Entra ID and Folia 1.20.6

1. Download the latest version of the HTL_auth plugin from the [releases page](https://github.com/TheTwoBoom/HTL_auth/releases).
2. Place the downloaded JAR file into your server's `plugins` directory.
3. Restart your server to load the plugin.

## Configuration

After the server restarts, a configuration file will be generated in the `plugins/HTL_auth` directory. You can edit this file to customize the plugin settings.

## Commands

- `/verify` - Authenticate with your microsoft account
- `/lookup <user>` - Lookup **email and full names** of other players (if enabled in config)

## Permissions

- `htlauth.join` - Allows players to move and take damage
- `htlauth.lookup` - Allows players to lookup **email and full names** of other players (if enabled in config)

## Demo
https://github.com/user-attachments/assets/6dcbb844-5c94-41cc-8e40-99b01fe4257d

## License

This project is licensed under the GNU Affero General Public License v3.0. See the [LICENSE](https://github.com/TheTwoBoom/HTL_auth/blob/main/LICENSE) file for details.

## Contributing

Contributions are welcome! Please fork this repository and submit pull requests.

## Support

For issues and feature requests, please use the [GitHub issues](https://github.com/TheTwoBoom/HTL_auth/issues) page.

## Disclaimer
This project/product/service is not affiliated with, endorsed by, or associated with Microsoft Corporation in any way. Microsoft and its products or services mentioned here are trademarks or registered trademarks of Microsoft Corporation in the United States and/or other countries. All information provided is for informational purposes only and should not be interpreted as representing or reflecting the views or opinions of Microsoft Corporation.
