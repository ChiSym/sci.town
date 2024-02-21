# inspector-server

A small server that forwards websocket messages from "producers" (processes that we want to inspect)
to "inspectors" (which will display the messages in a web interface).

A workflow might look like:

1. User starts a Python or Julia process, possibly using a notebook like Jupyter or Pluto.
2. Using an sdk in Python/Julia (TODO), the user spawns a process running the `./out/server`,
   specifying a `--port`. The sdk tracks the port internally and creates a websocket connection to
   the server which will be used to send traces (or other data) to the server.
3. The user opens a web browser and navigates to `http://localhost:8001` (or wherever the inspector
   UI is running). The inspector UI will connect to the server and display the traces. 

For the inspector UI, run `bb dev` in the `editor2` directory. This will build the cljs. When complete,
you can view the inspector UI at `http://localhost:8001`.

## Development

---
To install dependencies:

```bash
bun install
```

To run and reload when source files change:

```bash
bb dev
```

To create an executable:

```bash 
bb release
```

To run the executable:

```bash 
./out/server --port 3000
```