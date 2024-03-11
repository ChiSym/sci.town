
using SimpleWebsockets
using UUIDs
using MsgPack

# Let's create a simple client struct:
struct InspectorClient
    host::String
    WebsocketClient::WebsocketClient
    serializer::Function
    deserializer::Function
    session_id::UUID
end

# The `websocket` field will hold our connection to the server. The `serializer` and `deserializer` fields will hold functions for converting between Julia types and JSON.

# Next, we need a constructor to connect to the server:

function InspectorClient(host)
    client = WebsocketClient()
    ended = Condition()

    listen(client, :connect) do connection
        listen(connection, :message) do message
            @info message
        end
        listen(connection, :close) do reason 
            @warn "Websocket connection closed" reason...
            notify(ended)
        end
        close(connection)
    end
    listen(client, :connectError) do err 
        notify(ended, err, error = true)
    end 
    @async open(client, "wss://" * url)
    serializer = pack
    deserializer = unpack
    session_id = uuid4()
    return InspectorClient(host, client, serializer, deserializer, session_id)
end

function inspect(client::InspectorClient, msg)
    send(client.WebsocketClient, client.serializer(msg))
end



#=
INSPECTOR_SERVER = "localhost:3000"
client = InspectorClient(INSPECTOR_SERVER)
client.WebsocketClient
inspect(client, "Hello, world")
=#
