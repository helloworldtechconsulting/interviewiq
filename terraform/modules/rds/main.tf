# =============================================================================
# modules/rds/main.tf
#
# RDS PostgreSQL 15 for InterviewIQ
#
# Prod  : Multi-AZ RDS instance (db.t4g.medium → db.m7g.large as scale grows)
# Dev   : Single-AZ RDS (db.t4g.micro) — no failover, cheaper
# Staging: Single-AZ (db.t4g.small) with Multi-AZ off
#
# Security:
#   - Isolated subnets only (no route to internet)
#   - Encrypted at rest (KMS)
#   - Encrypted in transit (require SSL)
#   - Automated backups (7d dev, 30d prod)
#   - Deletion protection in prod
#   - Password in Secrets Manager (NOT hardcoded)
# =============================================================================

# ── Subnet Group (isolated tier — no internet route) ─────────────────────────

resource "aws_db_subnet_group" "main" {
  name        = "${var.project}-${var.env}-db-subnet-group"
  subnet_ids  = var.isolated_subnet_ids
  description = "Isolated subnets for ${var.project} ${var.env} RDS"
  tags        = merge(var.tags, { Name = "${var.project}-${var.env}-db-subnet-group" })
}

# ── Security Group ────────────────────────────────────────────────────────────

resource "aws_security_group" "rds" {
  name        = "${var.project}-${var.env}-rds-sg"
  description = "Allow PostgreSQL traffic from ECS tasks only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from ECS tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.ecs_security_group_id]
  }

  # No egress needed — RDS only receives connections
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound (for RDS → CloudWatch metrics)"
  }

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-rds-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

# ── Parameter Group (PostgreSQL tuning) ───────────────────────────────────────

resource "aws_db_parameter_group" "main" {
  family = "postgres15"
  name   = "${var.project}-${var.env}-pg15-params"

  # Enforce SSL connections — Spring Boot uses ssl=true in JDBC URL
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  # Performance: enable pg_stat_statements for query monitoring
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  # Log slow queries (threshold: 1s in prod, 100ms in dev for visibility)
  parameter {
    name         = "log_min_duration_statement"
    value        = var.env == "prod" ? "1000" : "100"
    apply_method = "immediate"
  }

  # Connection pooling via pgBouncer is future work; allow 200 max now
  parameter {
    name         = "max_connections"
    value        = "200"
    apply_method = "pending-reboot"
  }

  tags = var.tags
}

# ── RDS Instance ──────────────────────────────────────────────────────────────

resource "aws_db_instance" "main" {
  identifier = "${var.project}-${var.env}-postgres"

  # Engine
  engine               = "postgres"
  engine_version       = "15.6"
  instance_class       = var.instance_class
  db_name              = var.db_name
  username             = var.db_username
  password             = var.db_password # Injected from Secrets Manager via module caller
  parameter_group_name = aws_db_parameter_group.main.name

  # Storage
  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage # autoscaling ceiling
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn
  iops                  = var.env == "prod" ? 3000 : null # gp3 baseline

  # Network
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false

  # High Availability
  multi_az = var.multi_az

  # Backups
  backup_retention_period   = var.backup_retention_days
  backup_window             = "02:00-03:00" # 2AM–3AM IST (UTC+5:30 = 20:30–21:30 UTC)
  maintenance_window        = "Mon:03:00-Mon:04:00"
  copy_tags_to_snapshot     = true
  skip_final_snapshot       = var.env != "prod"
  final_snapshot_identifier = var.env == "prod" ? "${var.project}-${var.env}-final-snapshot" : null
  delete_automated_backups  = var.env != "prod"

  # Protection
  deletion_protection = var.env == "prod"

  # Monitoring
  monitoring_interval          = var.env == "prod" ? 60 : 0 # Enhanced Monitoring (prod only)
  monitoring_role_arn          = var.env == "prod" ? aws_iam_role.rds_monitoring[0].arn : null
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  performance_insights_enabled = var.env == "prod"
  performance_insights_kms_key_id        = var.env == "prod" ? var.kms_key_arn : null
  performance_insights_retention_period  = var.env == "prod" ? 7 : null

  # Auto minor version upgrades (patch releases only — major requires manual review)
  auto_minor_version_upgrade = true
  apply_immediately          = var.env != "prod" # prod: wait for maintenance window

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-postgres" })
}

# ── Enhanced Monitoring IAM Role (prod only) ──────────────────────────────────

resource "aws_iam_role" "rds_monitoring" {
  count = var.env == "prod" ? 1 : 0
  name  = "${var.project}-${var.env}-rds-monitoring-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  count      = var.env == "prod" ? 1 : 0
  role       = aws_iam_role.rds_monitoring[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# ── Read Replica (prod only — future analytics/read scaling) ──────────────────

resource "aws_db_instance" "replica" {
  count = var.create_read_replica ? 1 : 0

  identifier             = "${var.project}-${var.env}-postgres-replica"
  replicate_source_db    = aws_db_instance.main.identifier
  instance_class         = var.replica_instance_class
  publicly_accessible    = false
  storage_encrypted      = true
  kms_key_id             = var.kms_key_arn
  vpc_security_group_ids = [aws_security_group.rds.id]
  skip_final_snapshot    = true
  deletion_protection    = false # replica is disposable
  auto_minor_version_upgrade = true

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-postgres-replica" })
}
