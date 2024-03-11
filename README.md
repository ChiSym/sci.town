## sci.town

A multiplayer notebook for exploratory learning and teaching in probabilistic computing.

### Development

Prerequisites:
 - [babashka](https://babashka.org)
 - [bun](https://bun.sh/docs/installation)

In `editor2`:
- install dependencies: `bb deps`
- start the dev server: `bb dev`

This will compile the project and start two dev servers:
- http://localhost:8000 for the editor
- http://localhost:3000 for the studio

The editor's dev server uses a local emulator for the Firebase database.

## Adding additional 3rd party libraries

Third party libraries currently require a PR to this repo. See `maria.editor.extensions.config`
and other namespaces in `maria.editor.extensions` for examples.

## Conventions 

- functions that return promises end with `+`, like `fetch-doc+`
- mutable things start with `!`, like `!state`

## Credits 

This software is a fork of [Maria.cloud](https://maria.cloud), a collaborative work by [@daveliepmann](https://twitter.com/daveliepmann), [@jackrusher](https://twitter.com/jackrusher), and [@mhuebert](https://twitter.com/mhuebert) (see MARIA.md) 
with support from [Clojurists Together](https://www.clojuriststogether.org).