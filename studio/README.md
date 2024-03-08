# studio-server

A small server that forwards websocket messages from "producers" (processes that we want to inspect)
to "inspectors" (which will display the messages in a web interface).

A workflow might look like:

1. User starts a Python or Julia process, possibly using a notebook like Jupyter or Pluto.
2. Using an sdk in Python/Julia (TODO), the user spawns a process running the `./out/server`,
   specifying a `--port`. The sdk tracks the port internally and creates a websocket connection to the
   server which will be used to send traces (or other data) to the server. 3. The user opens a web
   browser and navigates to `http://localhost:3000`.  The Studio UI will connect to the server and
   display the traces.

## Development

In the `../editor2` directory, run `bb deps` and then `bb dev` to compile and run Studio.
The server is in this directory (implemented in Bun) and the viewer is part of the `editor2` cljs build.

## Release

To create an executable:

```bash 
bb release
```

To run the executable:

```bash 
./out/server --port 3000 # this is the default port
```

TODO - when we implement SDKs we'll need to decide how to package the server along with its
necessary files, located in `editor2/public`.