################################################################################
# VPC
################################################################################

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.project_name}-${var.environment}-vpc"
  }
}

################################################################################
# Internet Gateway
################################################################################

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-${var.environment}-igw"
  }
}

################################################################################
# Public Subnets (ALB only)
################################################################################

resource "aws_subnet" "public" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.project_name}-${var.environment}-public-${var.availability_zones[count.index]}"
    Tier = "public"
  }
}

################################################################################
# Private App Subnets
################################################################################

resource "aws_subnet" "private_app" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = var.availability_zones[count.index]

  tags = {
    Name = "${var.project_name}-${var.environment}-app-${var.availability_zones[count.index]}"
    Tier = "app"
  }
}

################################################################################
# Private Data Subnets
################################################################################

resource "aws_subnet" "private_data" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 20)
  availability_zone = var.availability_zones[count.index]

  tags = {
    Name = "${var.project_name}-${var.environment}-data-${var.availability_zones[count.index]}"
    Tier = "data"
  }
}

################################################################################
# NAT Gateway
################################################################################

resource "aws_eip" "nat" {
  domain = "vpc"

  tags = {
    Name = "${var.project_name}-${var.environment}-nat-eip"
  }
}

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id

  tags = {
    Name = "${var.project_name}-${var.environment}-nat"
  }

  depends_on = [aws_internet_gateway.main]
}

################################################################################
# Route Tables
################################################################################

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  count          = length(var.availability_zones)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-private-rt"
  }
}

resource "aws_route_table_association" "private_app" {
  count          = length(var.availability_zones)
  subnet_id      = aws_subnet.private_app[count.index].id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_data" {
  count          = length(var.availability_zones)
  subnet_id      = aws_subnet.private_data[count.index].id
  route_table_id = aws_route_table.private.id
}

################################################################################
# VPC Endpoints
################################################################################

resource "aws_vpc_endpoint" "s3" {
  vpc_id       = aws_vpc.main.id
  service_name = "com.amazonaws.${var.aws_region}.s3"

  route_table_ids = [
    aws_route_table.private.id,
    aws_route_table.public.id,
  ]

  tags = {
    Name = "${var.project_name}-${var.environment}-s3-endpoint"
  }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id       = aws_vpc.main.id
  service_name = "com.amazonaws.${var.aws_region}.dynamodb"

  route_table_ids = [
    aws_route_table.private.id,
  ]

  tags = {
    Name = "${var.project_name}-${var.environment}-dynamodb-endpoint"
  }
}

resource "aws_security_group" "vpc_endpoints" {
  name_prefix = "${var.project_name}-${var.environment}-vpce-"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-vpce-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_endpoint" "ecr_api" {
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.api"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private_app[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints.id]

  tags = {
    Name = "${var.project_name}-${var.environment}-ecr-api-endpoint"
  }
}

resource "aws_vpc_endpoint" "ecr_dkr" {
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.dkr"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private_app[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints.id]

  tags = {
    Name = "${var.project_name}-${var.environment}-ecr-dkr-endpoint"
  }
}

resource "aws_vpc_endpoint" "cloudwatch_logs" {
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.logs"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private_app[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints.id]

  tags = {
    Name = "${var.project_name}-${var.environment}-logs-endpoint"
  }
}

################################################################################
# Security Groups
#
# The ALB, app and data groups carry NO inline rules; every rule is a
# standalone aws_vpc_security_group_{ingress,egress}_rule below. That breaks
# the alb <-> app reference cycle (the ALB egress names the app group and the
# app ingress names the ALB group) and avoids mixing inline and standalone
# rules on one group. Terraform removes the AWS default allow-all egress rule
# from every group it creates, so a group with no egress rule resource below
# has no egress at all (AWS-0104).
################################################################################

resource "aws_security_group" "alb" {
  name_prefix = "${var.project_name}-${var.environment}-alb-"
  description = "ScholarChain ALB: HTTP/80 from operator CIDRs only; egress tcp/8080 to the app tier only"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-${var.environment}-alb-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# N16 (restrictive option, pending human decision): HTTP/80 only from the
# operator CIDRs in var.alb_ingress_cidrs (required, no default; 0.0.0.0/0,
# ::/0 and any /0 are rejected by the root variable validation). There is no
# 443 rule because no HTTPS listener or certificate exists; add both together
# when HTTPS is approved. The API has no authentication (service-engineer,
# required before prod).
resource "aws_vpc_security_group_ingress_rule" "alb_http_from_operators" {
  for_each          = toset(var.alb_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from operator CIDR ${each.value}"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = each.value

  tags = {
    Name = "${var.project_name}-${var.environment}-alb-in-80"
  }
}

# AWS-0104: the ALB only forwards to the app tier's container port.
resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to app tier container port"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.app.id

  tags = {
    Name = "${var.project_name}-${var.environment}-alb-out-8080"
  }
}

resource "aws_security_group" "app" {
  name_prefix = "${var.project_name}-${var.environment}-app-"
  description = "ScholarChain ECS tasks: 8080 from ALB; egress 443 (AWS APIs) and 8182 (Neptune) only"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-${var.environment}-app-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "Traffic from ALB"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id

  tags = {
    Name = "${var.project_name}-${var.environment}-app-in-8080"
  }
}

# AWS-0104 (narrowed): every AWS API the tasks call is HTTPS on 443 -- the
# S3 and DynamoDB gateway endpoints, the ECR/logs interface endpoints, and,
# through the NAT gateway, Bedrock, S3 Vectors, Secrets Manager, STS and SQS.
# 0.0.0.0/0 is required for the NAT-routed services (no interface endpoints
# for them). The tasks serve on 8080 and call no other task over the network
# (all five services run the same in-process server; no inter-service HTTP
# client exists under modules/*/src/main). DNS to the VPC resolver, the task
# metadata endpoint and Amazon Time Sync are link-local / resolver traffic and
# are not filtered by security groups.
resource "aws_vpc_security_group_egress_rule" "app_https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS to AWS APIs (VPC endpoints and NAT)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"

  tags = {
    Name = "${var.project_name}-${var.environment}-app-out-443"
  }
}

resource "aws_vpc_security_group_egress_rule" "app_to_neptune" {
  security_group_id            = aws_security_group.app.id
  description                  = "Neptune openCypher (SigV4) on 8182"
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
  referenced_security_group_id = aws_security_group.data.id

  tags = {
    Name = "${var.project_name}-${var.environment}-app-out-8182"
  }
}

# Data tier (Neptune). Ingress 8182 from the app tier and EMR master only.
# AWS-0104 / N26: exactly ONE egress rule (data_to_s3_endpoint below):
# tcp/443 to the S3 gateway endpoint prefix list, so the Neptune bulk loader
# can read load files from S3 through the gateway endpoint. Nothing else.
resource "aws_security_group" "data" {
  name_prefix = "${var.project_name}-${var.environment}-data-"
  description = "ScholarChain Neptune: 8182 from app tier and EMR master; egress 443 to S3 gateway endpoint only"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-${var.environment}-data-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# N26: the Neptune bulk loader (NeptuneClient loader requests; the loader
# role is attached in modules/neptune) fetches its source files from S3.
# Neptune runs in the private_data subnets, whose route table (private) sends S3 traffic to
# the gateway endpoint aws_vpc_endpoint.s3, so the only egress the data tier
# needs is HTTPS to that endpoint's AWS-managed prefix list.
resource "aws_vpc_security_group_egress_rule" "data_to_s3_endpoint" {
  security_group_id = aws_security_group.data.id
  description       = "HTTPS to S3 gateway endpoint (Neptune bulk loader)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id

  tags = {
    Name = "${var.project_name}-${var.environment}-data-out-s3"
  }
}

resource "aws_vpc_security_group_ingress_rule" "data_from_app" {
  security_group_id            = aws_security_group.data.id
  description                  = "Neptune from app tier"
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
  referenced_security_group_id = aws_security_group.app.id

  tags = {
    Name = "${var.project_name}-${var.environment}-data-in-app"
  }
}

# EMR security groups: full egress is kept ON PURPOSE. Phase 12 review Pass 3
# (Trivy triage, AWS-0104 rows for emr_master/emr_core) classified it as
# acceptable by design: EMR nodes need S3, the EMR control plane and package
# repositories, and AWS documents full egress for EMR managed security groups.
resource "aws_security_group" "emr_master" {
  name_prefix = "${var.project_name}-${var.environment}-emr-master-"
  vpc_id      = aws_vpc.main.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-emr-master-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "emr_core" {
  name_prefix = "${var.project_name}-${var.environment}-emr-core-"
  vpc_id      = aws_vpc.main.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-emr-core-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Allow EMR master <-> core communication
resource "aws_security_group_rule" "emr_master_from_core" {
  type                     = "ingress"
  from_port                = 0
  to_port                  = 65535
  protocol                 = "tcp"
  security_group_id        = aws_security_group.emr_master.id
  source_security_group_id = aws_security_group.emr_core.id
}

resource "aws_security_group_rule" "emr_core_from_master" {
  type                     = "ingress"
  from_port                = 0
  to_port                  = 65535
  protocol                 = "tcp"
  security_group_id        = aws_security_group.emr_core.id
  source_security_group_id = aws_security_group.emr_master.id
}

# Allow EMR to access Neptune (standalone rule type matches the data group's
# other rules; the data group has no inline rules).
resource "aws_vpc_security_group_ingress_rule" "data_from_emr" {
  security_group_id            = aws_security_group.data.id
  description                  = "Neptune from EMR master"
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
  referenced_security_group_id = aws_security_group.emr_master.id

  tags = {
    Name = "${var.project_name}-${var.environment}-data-in-emr"
  }
}
