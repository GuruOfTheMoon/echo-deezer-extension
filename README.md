[![Latest Version](https://img.shields.io/github/v/release/GuruOfTheMoon/echo-deezer-extension?color=blue)](https://github.com/GuruOfTheMoon/echo-deezer-extension/releases/latest)
# Deezer All Songs Extension for Echo

This is a fork of [LuftVerbot/echo-deezer-extension](https://github.com/LuftVerbot/echo-deezer-extension) for the [Echo](https://github.com/brahmkshatriya/echo) music player. It adds complete artist discography support while keeping Deezer playback, library and login features.

> The extension id is `deezerall`, so it can be installed alongside the official Deezer extension instead of conflicting with it.

## Features

- **High-Quality Audio Support**: FLAC, 320kbps, or 128kbps without a premium account(May sometimes be unstable, due to accounts on the server).
- **Complete Discography**: Every artist profile gets an **All Songs** playlist that lazily aggregates albums, EPs and singles, deduplicated across standard and deluxe editions.
- **Multiple Login Options**: Choose from three different ways to sign in.
- **Personal Library Access**: Access your Deezer library directly within Echo.
- **Diverse Radio Options**: Explore various radio features, including Flow and more.

## Screenshots

<div align="center" style="text-align: center;">
  <table>
    <tr>
      <td align="center" width="260px">
        <img src="https://github.com/user-attachments/assets/58df2ca3-0bce-4ed8-96c6-fc6efc2ee9c6" alt="Home Screen" width="250"/><br>
        🎵 Home Screen<br>
        <span>All your music essentials in one place.</span>
      </td>
      <td align="center" width="260px">
        <img src="https://github.com/user-attachments/assets/684c0492-807f-4886-811d-0cdd9dd2d513" alt="Search & Discover" width="250"/><br>
        🔍 Search & Discover<br>
        <span>Find and explore music just like on Deezer.</span>
      </td>
      <td align="center" width="260px">
        <img src="https://github.com/user-attachments/assets/63b55a3b-725d-4f7e-b28f-99a9bd873345" alt="Your Deezer Library" width="250"/><br>
        ❤️ Your Deezer Library<br>
        <span>Access and manage your favorite tracks within Echo.</span>
      </td>
    </tr>
  </table>
</div>

## Installation

Ensure you have Echo installed on your device before proceeding. Currently it's only possible to get it from [Discord](https://discord.gg/J3WvbBUU8Z).

### Import via link (recommended)

1. Open Echo and go to **Extensions → Add**.
2. Paste this link:

   ```
   https://raw.githubusercontent.com/GuruOfTheMoon/echo-deezer-extension/main/extensions.json
   ```

3. Select **Deezer All Songs** and download it.
4. When the installer asks, choose **File** installation to load it as an Echo extension. Choose **App** only if you also want the Deezer link handler installed as a separate app.
5. Launch Echo and select the extension.

The extension will check this repository's GitHub releases for updates automatically.

### Manual install

1. Download the latest `.eapk` from the [releases page](https://github.com/GuruOfTheMoon/echo-deezer-extension/releases/latest).
2. Open it with Echo and follow the on-screen installer.

### Building locally

```bash
./gradlew assembleRelease
```

A signed release requires the `KEYSTORE_B64` and `PASSWORD` repository secrets used by the GitHub Actions release workflow.

## FAQ

### Why?

> *"I always wanted to create a successor of Freezer that could follow its footsteps, and Echo gave me a good opportunity to start with it."*

### How is FLAC/320 possible without a Premium Account?

> *"Well, I host a server that gives back the premium songs. It was created by [@uhwot](https://github.com/uhwot), and I simply stumbled upon it while doing research about stuff for Deezer.*"

## Acknowledgements

- **[Echo](https://github.com/brahmkshatriya/echo)**: Created by [@brahmkshatriya](https://github.com/brahmkshatriya).
- **[dzmedia](https://uhwotgit.fly.dev/uhwot/dzmedia)**: Created by [@uhwot](https://github.com/uhwot).
