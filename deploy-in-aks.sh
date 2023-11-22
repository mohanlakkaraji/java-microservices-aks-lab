#!/bin/bash

# Read parameters from input
acr="$1"
commitsha="$2"
namespace="$3"

service="spring-petclinic-config-server"
tag="latest"

kubectl apply -f src/spring-petclinic-config-server/k8s/configmap.yaml -n $namespace

IMAGE="$acr\/$service:$tag"
echo "Deploying $service with image $IMAGE"

sed -e 's/#image#/'$IMAGE'/g' -e \
    's/#appname#/'$service'/g' \
    src/$service/k8s/application.yaml \
    | kubectl apply -f - -n $namespace

sleep 10

# List of services
services=("spring-petclinic-discovery-server" "spring-petclinic-customers-service" "spring-petclinic-vets-service" "spring-petclinic-visits-service" "spring-petclinic-api-gateway" "spring-petclinic-admin-server")

for service in "${services[@]}"
do
    IMAGE="$acr\/$service:$commitsha"
    echo "Deploying $service with image $IMAGE"
    
    sed -e 's/#image#/'$IMAGE'/g' -e \
        's/#appname#/'$service'/g' \
        src/$service/k8s/application.yaml \
        | kubectl apply -f - -n $namespace
done

