DIR=$(realpath "$(dirname "${BASH_SOURCE[0]}")")
black --line-length 79 --verbose --exclude="dev-env|grpc_client_server/grpc_generated" $DIR\..\src
