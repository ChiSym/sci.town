
using HTTP
using UUIDs
using MsgPack

# Let's create a simple client struct:
struct InspectorClient
    host::String
    serializer::Function
    session_id::String
end

function InspectorClient(host)
    serializer = pack
    session_id = string(uuid4())
    return InspectorClient(host, serializer, session_id)
end

function inspect(client::InspectorClient, msg)
    payload = client.serializer(["log", client.session_id, msg])
    url = client.host * "/log/" * client.session_id
    response = HTTP.post(url, body=payload)
end


# INSPECTOR_SERVER = "http://localhost:3000"
# client = InspectorClient(INSPECTOR_SERVER)

# inspect(client, "Hello, world")


# TODO 
# - spawn server