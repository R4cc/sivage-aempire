# Sivage

Sivage is a server-side Fabric mod for modern Minecraft which gives players the ability to use **Custom Image**'s from the internet within the in-game world.

Compared to mods like [Image2Map](https://modrinth.com/mod/image2map) or [Map4Image](https://modrinth.com/mod/map4image) from which Sivage takes heavy inspiration, this tries to provide a more **survival friendly approach** by providing **craftable items** and **GUIs**, rather than commands.

***

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">
    <h3>Features of <a href="https://modrinth.com/mod/sivage">Sivage</a></h3>
    <p>Here are several aspects of Sivage, also serving as a small starting guide:</p>
    <table>
        <tr>
            <td><img src="https://cdn.modrinth.com/data/dMOPQTBa/images/9b56bbfc93ede2c51e940571687ef89bf5c3db64.png"  alt="Auto Crafter crafting  a Custom Image with 8 Sticks and a Nametag at the center."/></td>
            <td><img src="https://cdn.modrinth.com/data/dMOPQTBa/images/d6713e222c5c48853fa5ebece6fdedcbdaed33c0.png" alt="Example of the UI for creating new Custom Images, consisting of a few options and sliders and an URL field."/></td>
            <td><img src="https://cdn.modrinth.com/data/dMOPQTBa/images/bfe014bb9cafdff1dfe5c7bc282ed068a20c6bc9.png" alt="A preview of how a transparent and framed image looks like."/></td>
        </tr>
        <tr>
            <td><b><i>Survival Friendly Integration!</i></b></td>
            <td><b><i>GUI for Easy Usage!</i></b></td>
            <td><b><i>Great Looking Images!</i></b></td>
        </tr>
    </table>
</div>

## Supported Formats

One common issue with images from the internet is ensuring which format they use. Sivage comes with great support for the most used formats: It supports **PNG**, **JPEG**, **WEBP**, **GIF**, **BMP**, **ICO**, **QOI**, **TIFF**, **FF**, **HDR** & **TGA**.

These should cover most – if not almost all image formats that you may encounter on the Internet!

> **Note** Animations are not supported! While formats like GIF or WEBP do work, they will only show the first frame in game.

## Configuration

This mod comes with a TOML file on the server at `config/sivage/server.toml`, allowing to adjust server owners which sources are allowed:

```toml
# Controls how this mod interacts with the internet.
[network]
# List of specifically allowed domains or wildcards. Overrules the blacklist.
whitelist = []
# List of specifically disallowed domains or wildcards. Overruled by the whitelist.
blacklist = ["localhost"]
# Specifies the maximum size in bytes that images are allowed to have while downloading. (0 means infinite)
# range: 0 - 2147483647
# default: 500000000
fileSizeLimit = 500000000

# This represents aspects of this mod that only have an affect within the game.
[game]
# When enabled, each player may have only one image generated at a time.
# default: false
playerLimit = false
# Maximum width and height, in blocks, for newly created images.
# range: 1 - 8
# default: 4
maxSize = 4
# When enabled, newly created images will use invisible item frames.
# default: false
invisibleFrames = false
# Maximum amount of placed images each player may own at a time. Set to 0 to disable this limit.
# range: 0 - 2147483647
# default: 16
maxImagesPerPlayer = 16
```

The image count limit counts each Sivage image once regardless of its block size, and removing an image frees that slot again. To disable this cap for a LuckPerms group or player, grant `sivage.limit.bypass`.

### LuckPerms role limits

When [LuckPerms](https://luckperms.net/) is installed, a player's inherited `sivage.max-images` metadata value overrides `game.maxImagesPerPlayer`. Set the metadata on a group (or directly on a player) to choose its cap:

```
/lp group vip meta set sivage.max-images 25
/lp group member meta set sivage.max-images 5
```

Use `0` for unlimited images. If LuckPerms is not installed, no metadata value is set, or the value is invalid or negative, Sivage uses `game.maxImagesPerPlayer` instead. LuckPerms' normal inheritance and meta-priority rules select the effective value when a player belongs to multiple groups.

> **Note** Both the white- and blacklist allow the usage of wildcards; To be more specific, `*` for everything and `*.example.com` for subdomain wildcards. IP Addresses and protocols not following HTTP(S) will always be rejected.

## Commands

Sivage provides a few server-side commands for admins:

- `/sivage item` gives yourself a Custom Image item.
- `/sivage item <for>` gives Custom Image items to one or more players.
- `/sivage check <image>` shows signature information for a specific Sivage image entity.
- `/sivage images list` lists all placed Sivage images with their image UUID, dimension, coordinates, owner UUID, and frame count.
- `/sivage images list <user>` lists placed Sivage images owned by a specific user.
- `/sivage images delete <user>` deletes all placed Sivage images owned by a specific user.

For the image list and delete commands, `<user>` may be an online player name or a UUID. Command access can be granted with these permission nodes (and `sivage.admin` grants all of them):

- `sivage.command.item` for `/sivage item` and `/sivage item <for>`.
- `sivage.command.images.list` for `/sivage images list [user]`.
- `sivage.command.images.delete` for `/sivage images delete <user>`.

Operators retain access through the existing gamemaster fallback.

## Development

In order for this mod to work with as many formats as possible on any JVM, the image processing part has been written in Rust and is being run with WebAssembly that has been compiled to java bytecode.

At the cost of slower processing times, compared to any other image processor that has been directly developed for the JVM,
this approach supports many formats and runs on pretty much any Java distribution, as it does not depend on ImageIO at all.

### Compiling

Follow these steps in order to be setup and ready!

- In order to compile this mod, the latest version of [Rust needs to be installed](https://rust-lang.org/tools/install/) in addition to an JDK.

- Then, ensure the wasm target is installed and stable is targeted by running these commands in CLI: `rustup default stable` & `rustup target add wasm32-unknown-unknown`.

- Finally, navigate to `/src/main/rust` and run `cargo build --target=wasm32-unknown-unknown --release --lib`!

- The compiled lib will be located at `/src/main/rust/target/wasm32-unknown-unknown/release/imaging.wasm`, where Gradle
automatically searches for the wasm file, when compiling the mod. **Otherwise, the Wasm2ClassTask task will fail**.

From this point on, the mod can be compiled as usual through the gradle/build task!

***

###### Copyright © 2026 Sammy L. Koch

###### Sivage is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, version 3 only of the License.

###### Sivage is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.

###### You should have received a copy of the GNU Lesser General Public License along with this mod. If not, see http://www.gnu.org/licenses/.
