
using HTTP
using UUIDs
using MsgPack

# Let's create a simple client struct:
struct StudioClient
    host::String
    serializer::Function
    session_id::String
end

function StudioClient(host)
    serializer = pack
    session_id = string(uuid4())
    return StudioClient(host, serializer, session_id)
end

function inspect(client::StudioClient, msg)
    payload = client.serializer(["log", client.session_id, msg])
    url = client.host * "/log/" * client.session_id
    response = HTTP.post(url, body=payload)
end


# STUDIO_HOST = "http://localhost:3000"
# client = StudioClient(STUDIO_HOST)
# inspect(client, "Hello, world")


# TODO 
# - spawn server