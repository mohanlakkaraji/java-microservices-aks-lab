# Java on AKS

## Lab 2

### Environment Setup

```bash
export UNIQUEID=$(openssl rand -hex 3)
export APPNAME=petclinic
export RESOURCE_GROUP=rg-$APPNAME-$UNIQUEID
export LOCATION=northeurope
```	

### Create Resource Group

```bash
az group create -g $RESOURCE_GROUP -l $LOCATION
```
### Create Azure Container Registry

```bash
MYACR=acr$APPNAME$UNIQUEID
az acr create \
    -n $MYACR \
    -g $RESOURCE_GROUP \
    --sku Basic
```

### Create Virtual Network

```bash
VIRTUAL_NETWORK_NAME=vnet-$APPNAME-$UNIQUEID
az network vnet create \
    --resource-group $RESOURCE_GROUP \
    --name $VIRTUAL_NETWORK_NAME \
    --location $LOCATION \
    --address-prefix 10.1.0.0/16

AKS_SUBNET_CIDR=10.1.0.0/24
az network vnet subnet create \
    --resource-group $RESOURCE_GROUP \
    --vnet-name $VIRTUAL_NETWORK_NAME \
    --address-prefixes $AKS_SUBNET_CIDR \
    --name aks-subnet

SUBNET_ID=$(az network vnet subnet show --resource-group $RESOURCE_GROUP --vnet-name $VIRTUAL_NETWORK_NAME --name aks-subnet --query id -o tsv)
```

### Create AKS Cluster

```bash
AKSCLUSTER=aks-$APPNAME-$UNIQUEID
az aks create \
    -n $AKSCLUSTER \
    -g $RESOURCE_GROUP \
    --location $LOCATION \
    --generate-ssh-keys \
    --attach-acr $MYACR \
    --vnet-subnet-id $SUBNET_ID
```

### Configure Github related stuff

#### Create Github Repo for config

[New repo for config (private)](https://github.com/dsanchor/java-microservices-aks-lab-config)

#### Create PAT

[Create a Personal accesss token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic)

Export the PAT as an environment variable:

```bash
export PAT=<your-pat>
export REPONAME=java-microservices-aks-lab-config.git
export USERNAME=<your-github-username>
```

Test PAT:

```bash
git clone https://$PAT@github.com/$USERNAME/$REPONAME
```

#### Copy config content to new github repo

```bash
cd java-microservices-aks-lab-config
curl -o api-gateway.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/config/api-gateway.yml
curl -o application.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/docs/02_lab_migrate/0203_application.yml
curl -o customers-service.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/config/customers-service.yml
curl -o discovery-server.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/config/discovery-server.yml
curl -o tracing-server.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/config/tracing-server.yml
curl -o vets-service.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/config/vets-service.yml
curl -o visits-service.yml https://raw.githubusercontent.com/Azure-Samples/java-microservices-aks-lab/main/config/visits-service.yml
git add .
git commit -m "Initial commit"
git push
```

#### Add new config repo to the app config server (variables)

In  [src/spring-petclinic-config-server/src/main/resources/application.yml](./src/spring-petclinic-config-server/src/main/resources/application.yml) modify the following:

```bash
uri: ${CONFIG_REPO}
username: ${CONFIG_REPO_USER}
password: ${CONFIG_REPO_PWD}
```

NOTE: These variables will be initialized later in the lab.

### Deploy Azure Database for MySQL Flexible Server

#### Create MySQL Flexible Server

```bash
export MYSQL_SERVER_NAME=mysql-$APPNAME-$UNIQUEID
export  MYSQL_ADMIN_USERNAME=myadmin
export  MYSQL_ADMIN_PASSWORD=<myadmin-password>
export  DATABASE_NAME=petclinic

az mysql flexible-server create \
    --admin-user myadmin \
    --admin-password ${MYSQL_ADMIN_PASSWORD} \
    --name ${MYSQL_SERVER_NAME} \
    --resource-group ${RESOURCE_GROUP} 
```

NOTE: Answer No to the questions about enabling public and your IP address

#### Create MySQL Database

```bash
 az mysql flexible-server db create \
     --server-name $MYSQL_SERVER_NAME \
     --resource-group $RESOURCE_GROUP \
     -d $DATABASE_NAME
```

#### Add firewall rule for allAzureIPs

```bash
az mysql flexible-server firewall-rule create \
     --rule-name allAzureIPs \
     --name ${MYSQL_SERVER_NAME} \
     --resource-group ${RESOURCE_GROUP} \
     --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0
```

#### Modify config to setup DB connection 

In your config repo, modify the lines 12 to 14 in the application.yaml with your MySQL server details:

```bash
echo "    url: jdbc:mysql://$MYSQL_SERVER_NAME.mysql.database.azure.com:3306/petclinic?useSSL=true
    username: myadmin
    password: $MYSQL_ADMIN_PASSWORD"
``` 

Paste the output and commit the changes to the repo.

```bash	
git add application.yml
git commit -m "Added MySQL connection details"
git push
```

NOTE: this credentials will be removed later on

### Prepare AKS

#### Get AKS credentials

```bash
az aks get-credentials -n $AKSCLUSTER -g $RESOURCE_GROUP
```

#### Create namespace

```bash
NAMESPACE=spring-petclinic
kubectl create ns $NAMESPACE
```

### Github actions

I have created a github action that will build and push the images to AC and then, deploy the applications to AKS. You can find it in [build-push-deploy.yml](.github/workflows/build-push-deploy.yml)

#### Get ACR credentials

Enable admin user in ACR:

```bash
az acr update -n $MYACR --admin-enabled true -g $RESOURCE_GROUP
```

And create 3 secrets in your repo with same name and values as the variables below:

```bash
ACR_PASSWORD=$(az acr credential show -n $MYACR -g $RESOURCE_GROUP --query "passwords[0].value" -o tsv)
ACR_USERNAME=$(az acr credential show -n $MYACR -g $RESOURCE_GROUP --query "username" -o tsv)
ACR_ENDPOINT=$(az acr show -n $MYACR -g $RESOURCE_GROUP --query "loginServer" -o tsv)
```
#### Create Service Principal

```bash
export SP_NAME=sp-$APPNAME-$UNIQUEID
export AZURE_CREDENTIALS=`az ad sp create-for-rbac --name $SP_NAME --role contributor \
                        --scopes /subscriptions/$(az account show --query id --output tsv)/resourceGroups/$RESOURCE_GROUP \
                        --json-auth`
```

Add a repo secret named AZURE_CREDENTAILS with above value.

#### Populate Config Server variables

Create the following repository secret for setting up the config server:

```bash
CONFIG_REPO=https://github.com/$USERNAME/$REPONAME
CONFIG_REPO_USER=$USERNAME
CONFIG_REPO_PWD=$PAT
```

### Run deployment (manually)

In case you want to force a deployment manually, run:

```bash
./deploy-in-aks.sh $MYACR.azurecr.io $(git rev-parse HEAD) $NAMESPACE $CONFIG_REPO $CONFIG_REPO_USER $CONFIG_REPO_PWD
```

### Test the app

#### Get service IP for the admin server

```bash
export ADMIN_SVCIP=$(kubectl get svc spring-petclinic-admin-server -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
```

#### Get service IP for the api gateway

```bash
export APIGATEWAY_SVCIP=$(kubectl get svc api-gateway -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
```

## Lab 3

### Enabling monitoring

#### Create infrastructure

Create Log Analitycs Workspace:

```bash	
WORKSPACE=la-$APPNAME-$UNIQUEID
az monitor log-analytics workspace create \
    --resource-group $RESOURCE_GROUP \
    --workspace-name $WORKSPACE
WORKSPACEID=$(az monitor log-analytics workspace show -n $WORKSPACE -g $RESOURCE_GROUP --query id -o tsv)

az aks enable-addons \
    -a monitoring \
    -n $AKSCLUSTER \
    -g $RESOURCE_GROUP \
    --workspace-resource-id $WORKSPACEID
```

Create Application Insights:

```bash
APPINSIGHTS=ai-$APPNAME-$UNIQUEID
az monitor app-insights component create \
    --app $APPINSIGHTS \
    --location $LOCATION \
    --resource-group $RESOURCE_GROUP \
    --application-type web \
    --kind web \
    --workspace $WORKSPACEID
```

#### Prepare app images and k8s manifests

Download agent:
    
```bash
services=("spring-petclinic-discovery-server" "spring-petclinic-customers-service" "spring-petclinic-vets-service" "spring-petclinic-visits-service" "spring-petclinic-api-gateway" "spring-petclinic-admin-server" "spring-petclinic-config-server")
wget https://github.com/microsoft/ApplicationInsights-Java/releases/download/3.4.12/applicationinsights-agent-3.4.12.jar -O /tmp/applicationinsights-agent-3.4.12.jar

for service in "${services[@]}"
do
    mkdir -f src/$service/appinsights
    cp /tmp/applicationinsights-agent-3.4.12.jar src/$service/appinsights/ai.jar

done
```

Add in your Dockerfile the following lines:

```bash
ARG APP_INSIGHTS_JAR

....
....

# Add App Insights jar to the container
ADD ${APP_INSIGHTS_JAR} ai.jar

# Run the jar file
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-javaagent:/ai.jar","-jar","/app.jar"]
```

Get the AI connection string:

```bash
AI_CONNECTIONSTRING=$(az monitor app-insights component show --app $APPINSIGHTS -g $RESOURCE_GROUP --query connectionString)
```

Add this line to the config server ConfigMap:

```bash
echo "  APPLICATIONINSIGHTS_CONNECTION_STRING: $AI_CONNECTIONSTRING"
```

Replace ConfigMap:
    
```bash
kubectl replace -f src/spring-petclinic-config-server/k8s/configmap.yaml -n $NAMESPACE
```

Add the following lines to the deployment of each service:

NOTE: I just hardcoded the appname for config-server, api-gateway and discovery-server.

```bash	
        - name: "APPLICATIONINSIGHTS_CONNECTION_STRING"
          valueFrom:
            configMapKeyRef:
              name: config-server
              key: APPLICATIONINSIGHTS_CONNECTION_STRING
        - name: "APPLICATIONINSIGHTS_CONFIGURATION_CONTENT"
          value: >-
            {
                "role": {   
                    "name": "#appname#"
                  }
            }
```

Push the changes to the repo and wait for the deployment to finish:

```bash
git add .github/workflows/ src
git commit -m "ready with app insights"
git push
```

## Lab 4

### Enabling key vault

#### Create a key vault

```bash
KEYVAULT_NAME=kv-$APPNAME-$UNIQUEID
az keyvault create \
    --name $KEYVAULT_NAME \
    --resource-group $RESOURCE_GROUP \
    --location $LOCATION \
    --sku standard
```

Add PAT as a secret:

```bash
GIT_PAT=<your PAT>
az keyvault secret set \
    --name GIT-PAT \
    --value $GIT_PAT \
    --vault-name $KEYVAULT_NAME
```

#### Enable workload identity in AKS

```bash
az aks update --enable-oidc-issuer --enable-workload-identity --name $AKSCLUSTER --resource-group $RESOURCE_GROUP
```

Set the oidc issuer:
    
```bash
export AKS_OIDC_ISSUER="$(az aks show -n $AKSCLUSTER -g $RESOURCE_GROUP --query "oidcIssuerProfile.issuerUrl" -otsv)"
echo $AKS_OIDC_ISSUER
```

Create a user assigned identity:

```bash
USER_ASSIGNED_IDENTITY_NAME=uid-$APPNAME-$UNIQUEID

az identity create --name "${USER_ASSIGNED_IDENTITY_NAME}" --resource-group "${RESOURCE_GROUP}" --location "${LOCATION}"

az identity show --resource-group "${RESOURCE_GROUP}" --name "${USER_ASSIGNED_IDENTITY_NAME}"
USER_ASSIGNED_CLIENT_ID="$(az identity show --resource-group "${RESOURCE_GROUP}" --name "${USER_ASSIGNED_IDENTITY_NAME}" --query 'clientId' -otsv)"
echo $USER_ASSIGNED_CLIENT_ID
```

Add persmissions to the identity to access the key vault:

```bash
az keyvault set-policy -g $RESOURCE_GROUP -n $KEYVAULT_NAME --key-permissions get --spn $USER_ASSIGNED_CLIENT_ID
az keyvault set-policy -g $RESOURCE_GROUP -n $KEYVAULT_NAME --secret-permissions get --spn $USER_ASSIGNED_CLIENT_ID
az keyvault set-policy -g $RESOURCE_GROUP -n $KEYVAULT_NAME --certificate-permissions get --spn $USER_ASSIGNED_CLIENT_ID
```

Add a service account that uses the identity:

```bash
SERVICE_ACCOUNT_NAME="workload-identity-sa"

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ServiceAccount
metadata:
  annotations:
    azure.workload.identity/client-id: "${USER_ASSIGNED_CLIENT_ID}"
  name: "${SERVICE_ACCOUNT_NAME}"
  namespace: "${NAMESPACE}"
EOF
```

Create the federated identity credential between the managed identity, the service account issuer, and the subject:

```bash
FEDERATED_IDENTITY_CREDENTIAL_NAME=fedid-$APPNAME-$UNIQUEID

az identity federated-credential create --name ${FEDERATED_IDENTITY_CREDENTIAL_NAME} --identity-name "${USER_ASSIGNED_IDENTITY_NAME}" --resource-group "${RESOURCE_GROUP}" --issuer "${AKS_OIDC_ISSUER}" --subject system:serviceaccount:"${NAMESPACE}":"${SERVICE_ACCOUNT_NAME}" --audience api://AzureADTokenExchange
```
#### Add Key Vault CSI driver to the cluster

```bash
az aks enable-addons --addons azure-keyvault-secrets-provider --name $AKSCLUSTER --resource-group $RESOURCE_GROUP
```

Validate driver pods are running:

```bash
kubectl get pods -n kube-system
```

#### Create a SecretProviderClass

```bash
ADTENANT=$(az account show --query tenantId --output tsv)

cat <<EOF | kubectl apply -n spring-petclinic -f -
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: azure-kvname-user-msi
spec:
  provider: azure
  secretObjects:
  - secretName: gitpatsecret
    type: Opaque
    data: 
    - objectName: gitpat
      key: gitpat
  parameters:
    usePodIdentity: "false"
    useVMManagedIdentity: "false" 
    clientID: $USER_ASSIGNED_CLIENT_ID 
    keyvaultName: $KEYVAULT_NAME
    cloudName: "" 
    objects: |
      array: 
        - |
          objectName: GIT-PAT
          objectType: secret   
          objectAlias: gitpat          
          objectVersion: ""  
    tenantId: $ADTENANT
EOF
```

Update [application.yml](src/spring-petclinic-config-server/src/main/resources/application.yml) to use GIT_PAT instead of CONFIG_REPO_PWD.

#### Connect to DB

We will connect to the database using a managed identity and worklod identity.

Create a user assigned identity:

```bash
DB_ADMIN_USER_ASSIGNED_IDENTITY_NAME=uid-dbadmin-$APPNAME-$UNIQUEID

az identity create --name "${DB_ADMIN_USER_ASSIGNED_IDENTITY_NAME}" --resource-group "${RESOURCE_GROUP}" --location "${LOCATION}"
```

Assign identity to the DB:

```bash
az mysql flexible-server identity assign \
    --resource-group $RESOURCE_GROUP \
    --server-name $MYSQL_SERVER_NAME \
    --identity $DB_ADMIN_USER_ASSIGNED_IDENTITY_NAME
```

Get the user info you are currently logged in:

```bash
CURRENT_USER=$(az account show --query user.name --output tsv)
echo $CURRENT_USER
CURRENT_USER_OBJECTID=$(az ad signed-in-user show --query id --output tsv)
echo $CURRENT_USER_OBJECTID
```

Create a DB admin for the current logged in user:

```bash
az mysql flexible-server ad-admin create \
    --resource-group $RESOURCE_GROUP \
    --server-name $MYSQL_SERVER_NAME \
    --object-id $CURRENT_USER_OBJECTID \
    --display-name $CURRENT_USER \
    --identity $DB_ADMIN_USER_ASSIGNED_IDENTITY_NAME
```

Create an sql file for creating a database user for the user assigned managed identity:

```bash
IDENTITY_LOGIN_NAME="mysql_conn"

cat <<EOF >/tmp/createuser.sql
SET aad_auth_validate_oids_in_tenant = OFF;
DROP USER IF EXISTS '${IDENTITY_LOGIN_NAME}'@'%';
CREATE AADUSER '${IDENTITY_LOGIN_NAME}' IDENTIFIED BY '${USER_ASSIGNED_CLIENT_ID}';
GRANT ALL PRIVILEGES ON ${DATABASE_NAME}.* TO '${IDENTITY_LOGIN_NAME}'@'%';
FLUSH privileges;
EOF
```

Get an access token for the database and execute the sql script with this access token:

```bash
RDBMS_ACCESS_TOKEN=$(az account get-access-token \
    --resource-type oss-rdbms \
    --query accessToken \
    --output tsv) 
echo $RDBMS_ACCESS_TOKEN

az mysql flexible-server execute \
    --name ${MYSQL_SERVER_NAME} \
    --admin-user ${CURRENT_USER} \
    --admin-password ${RDBMS_ACCESS_TOKEN} \
    --file-path "/tmp/createuser.sql"
```

Change jdbc dependency in pom.xml to replace the regular mysql connector by the azure one:

(in customers, vets and visits pom.xml)

```bash
     <dependency>
       <groupId>com.azure.spring</groupId>
       <artifactId>spring-cloud-azure-starter-jdbc-mysql</artifactId>
       <scope>runtime</scope>
     </dependency>
```

In parent pom.xml, add the following dependency  in dependencies management:

```bash
        <dependency>
           <groupId>com.azure.spring</groupId>
           <artifactId>spring-cloud-azure-dependencies</artifactId>
           <version>5.2.0</version>
           <type>pom</type>
           <scope>import</scope>
         </dependency>
```

## Lab 5

### Integration with Service Bus

In order to support JMS 2.0 messaging, you need to create the Service Bus namespace with the Premium SKU. To ensure the microservices can retrieve the namespace value, add a connection string to your Service Bus namespace in the Key Vault instance that you provisioned earlier in this lab.

NOTE: As a more secure alternative, you could use managed identities associated with your microservices to connect directly to the Service Bus namespace. However, in this lab, you will store the connection string in your Key Vault.

#### Add configuration in config server

Add the following lines to the application.yml of the config repo:

```bash
  jms:
    servicebus:
      connection-string: ${spring.jms.servicebus.connectionstring}
      idle-timeout: 60000
      pricing-tier: premium
```

#### Create Service Bus namespace

```bash
SERVICEBUS_NAMESPACE=sb-$APPNAME-$UNIQUEID

az servicebus namespace create \
    --resource-group $RESOURCE_GROUP \
    --name $SERVICEBUS_NAMESPACE \
    --location $LOCATION \
    --sku Premium
```

#### Create Service Bus queue

```bash
az servicebus queue create \
    --resource-group $RESOURCE_GROUP \
    --namespace-name $SERVICEBUS_NAMESPACE \
    --name visits-requests
```

#### Add connection string to Key Vault

```bash
SERVICEBUS_CONNECTIONSTRING=$(az servicebus namespace authorization-rule keys list \
    --resource-group $RESOURCE_GROUP \
    --namespace-name $SERVICEBUS_NAMESPACE \
    --name RootManageSharedAccessKey \
    --query primaryConnectionString \
    --output tsv)
az keyvault secret set \
    --name SPRING-JMS-SERVICEBUS-CONNECTIONSTRING \
    --value $SERVICEBUS_CONNECTIONSTRING \
    --vault-name $KEYVAULT_NAME
```

#### Try it using spring-petclinic-messaging-emulator project

Add <module>spring-petclinic-messaging-emulator</module> in parent pom.xml.


Modify SecretProviderClass to add the following:

```bash
cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: azure-kvname-user-msi
spec:
  provider: azure
  secretObjects:
  - secretName: gitpatsecret
    type: Opaque
    data: 
    - objectName: gitpat
      key: gitpat
  - secretName: sbsecret
    type: Opaque
    data: 
    - objectName: sbconn
      key: sbconn
  parameters:
    usePodIdentity: "false"
    useVMManagedIdentity: "false" 
    clientID: $USER_ASSIGNED_CLIENT_ID 
    keyvaultName: $KEYVAULT_NAME
    cloudName: "" 
    objects: |
      array:
        - |
          objectName: SPRING-JMS-SERVICEBUS-CONNECTIONSTRING
          objectType: secret   
          objectAlias: sbconn       
          objectVersion: ""  
        - |
          objectName: GIT-PAT
          objectType: secret   
          objectAlias: gitpat          
          objectVersion: ""  
    tenantId: $ADTENANT
EOF
```