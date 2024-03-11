


module GenStudio

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
    serializer = MsgPack.pack
    session_id = string(UUIDs.uuid4())
    return StudioClient(host, serializer, session_id)
end

function inspect(client::StudioClient, msg)
    payload = client.serializer(["log", client.session_id, msg])
    url = client.host * "/log/" * client.session_id
    response = HTTP.post(url, body=payload)
end

function(client::StudioClient)(msg) 
    inspect(client, msg)
end

end


#= Usage
using GenStudio.GenStudio
studio = GenStudio.StudioClient("http://localhost:3000")
studio("foo")
=#

# TODO 
# - spawn server