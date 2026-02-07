#!/usr/bin/env bash
set -euo pipefail

# Launch 2 EFA-enabled EC2 instances in a cluster placement group.
# This mirrors the steps used for the current RDMA/EFA setup.

# Use the default AWS CLI profile unless AWS_PROFILE is explicitly set.
AWS_PROFILE="${AWS_PROFILE:-}"
AWS_REGION="${AWS_REGION:-us-east-1}"
AZ="${AZ:-us-east-1a}"
VPC_ID="${VPC_ID:-}"
SUBNET_ID="${SUBNET_ID:-}"
# Override these defaults if you have existing resources you want to reuse.
KEY_NAME="${KEY_NAME:-efa-rdma-key}"
KEY_PATH="${KEY_PATH:-$HOME/.ssh/${KEY_NAME}.pem}"
PG_NAME="${PG_NAME:-efa-rdma-pg}"
SG_NAME="${SG_NAME:-efa-rdma-sg}"
INSTANCE_TYPE="${INSTANCE_TYPE:-c5n.9xlarge}"
AMI_ID="${AMI_ID:-}"
SSH_CIDR="${SSH_CIDR:-}"

if ! command -v aws >/dev/null 2>&1; then
  echo "aws cli not found" >&2
  exit 1
fi

if [ -z "$SSH_CIDR" ]; then
  if command -v curl >/dev/null 2>&1; then
    SSH_CIDR="$(curl -s https://checkip.amazonaws.com)/32"
  else
    echo "SSH_CIDR not set and curl not available to detect public IP" >&2
    exit 1
  fi
fi

if [ -n "$AWS_PROFILE" ]; then
  export AWS_PROFILE
fi
export AWS_REGION

if [ -z "$VPC_ID" ]; then
  VPC_ID="$(aws ec2 describe-vpcs --region "$AWS_REGION" \
    --filters Name=isDefault,Values=true \
    --query 'Vpcs[0].VpcId' --output text)"
fi

if [ -z "$SUBNET_ID" ]; then
  SUBNET_ID="$(aws ec2 describe-subnets --region "$AWS_REGION" \
    --filters Name=vpc-id,Values="$VPC_ID" Name=availability-zone,Values="$AZ" \
    --query 'Subnets[0].SubnetId' --output text)"
fi

if [ "$SUBNET_ID" = "None" ] || [ -z "$SUBNET_ID" ]; then
  echo "No subnet found in VPC $VPC_ID for AZ $AZ" >&2
  exit 1
fi

aws ec2 create-placement-group --region "$AWS_REGION" \
  --group-name "$PG_NAME" --strategy cluster || true

if aws ec2 describe-key-pairs --region "$AWS_REGION" --key-names "$KEY_NAME" >/dev/null 2>&1; then
  if [ ! -f "$KEY_PATH" ]; then
    echo "Key pair $KEY_NAME exists in AWS but $KEY_PATH is missing." >&2
    echo "Import the key or use a new KEY_NAME." >&2
    exit 1
  fi
else
  umask 077
  aws ec2 create-key-pair --region "$AWS_REGION" --key-name "$KEY_NAME" \
    --query 'KeyMaterial' --output text > "$KEY_PATH"
fi

SG_ID="$(aws ec2 describe-security-groups --region "$AWS_REGION" \
  --filters Name=group-name,Values="$SG_NAME" Name=vpc-id,Values="$VPC_ID" \
  --query 'SecurityGroups[0].GroupId' --output text)"

if [ "$SG_ID" = "None" ] || [ -z "$SG_ID" ]; then
  SG_ID="$(aws ec2 create-security-group --region "$AWS_REGION" \
    --group-name "$SG_NAME" --description "EFA RDMA test" --vpc-id "$VPC_ID" \
    --query 'GroupId' --output text)"
  aws ec2 authorize-security-group-ingress --region "$AWS_REGION" \
    --group-id "$SG_ID" --protocol -1 --source-group "$SG_ID" || true
  aws ec2 authorize-security-group-ingress --region "$AWS_REGION" \
    --group-id "$SG_ID" --protocol tcp --port 22 --cidr "$SSH_CIDR" || true
fi

if [ -z "$AMI_ID" ]; then
  AMI_ID="$(aws ssm get-parameter --region "$AWS_REGION" \
    --name /aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2 \
    --query 'Parameter.Value' --output text)"
fi

INSTANCE_IDS="$(aws ec2 run-instances --region "$AWS_REGION" \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --count 2 \
  --key-name "$KEY_NAME" \
  --placement "GroupName=$PG_NAME" \
  --network-interfaces "DeviceIndex=0,SubnetId=$SUBNET_ID,Groups=$SG_ID,InterfaceType=efa,AssociatePublicIpAddress=true" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=efa-rdma}]" \
  --query 'Instances[].InstanceId' --output text)"

echo "Instance IDs: $INSTANCE_IDS"
aws ec2 wait instance-running --region "$AWS_REGION" --instance-ids $INSTANCE_IDS
aws ec2 describe-instances --region "$AWS_REGION" --instance-ids $INSTANCE_IDS \
  --query 'Reservations[].Instances[].[InstanceId,PrivateIpAddress,PublicIpAddress]' --output table
