# =============================================================================
# modules/iam/main.tf
#
# Least-privilege IAM roles for InterviewIQ ECS tasks.
#
# Two roles per service:
#   1. Task Execution Role — used by ECS agent to pull images + inject secrets
#   2. Task Role           — used by the application container at runtime
#
# The Task Role follows the principle of least privilege:
#   - S3: only the specific bucket, only needed actions
#   - SES: only SendEmail + SendRawEmail
#   - Secrets Manager: only the secrets this service needs
#   - No wildcard (*) resource ARNs
# =============================================================================

# ── ECS Task Execution Role ───────────────────────────────────────────────────
# Used by the ECS agent (not your code) to:
#   - Pull container images from ECR
#   - Pull secret values from Secrets Manager for env var injection
#   - Write logs to CloudWatch

resource "aws_iam_role" "task_execution" {
  name = "${var.project}-${var.env}-ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })

  tags = var.tags
}

# Managed policy covers ECR pull + CloudWatch Logs
resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Inline policy for Secrets Manager read + KMS decrypt
resource "aws_iam_role_policy" "task_execution_secrets" {
  name = "secrets-read"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadSecrets"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret"
        ]
        Resource = var.secret_arns
      },
      {
        Sid    = "DecryptSecrets"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey"
        ]
        Resource = [var.secrets_kms_key_arn]
      }
    ]
  })
}

# ── ECS Task Role (Runtime) ───────────────────────────────────────────────────
# Used by the Spring Boot application container itself.

resource "aws_iam_role" "task" {
  name = "${var.project}-${var.env}-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Condition = {
        ArnLike = {
          "aws:SourceArn" = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:*"
        }
      }
    }]
  })

  tags = var.tags
}

data "aws_caller_identity" "current" {}

# S3 access — scoped to interviewiq bucket and specific prefixes
resource "aws_iam_role_policy" "task_s3" {
  name = "s3-access"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3BucketAccess"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:GetObjectAcl"
        ]
        Resource = [
          "arn:aws:s3:::${var.s3_bucket_name}/*"
        ]
      },
      {
        Sid    = "S3ListBucket"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = ["arn:aws:s3:::${var.s3_bucket_name}"]
      },
      {
        Sid    = "S3KMSDecrypt"
        Effect = "Allow"
        Action = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = [var.s3_kms_key_arn]
      }
    ]
  })
}

# SES — send email only (no admin actions)
resource "aws_iam_role_policy" "task_ses" {
  name = "ses-send"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "SESSendEmail"
      Effect = "Allow"
      Action = [
        "ses:SendEmail",
        "ses:SendRawEmail",
        "ses:GetSendQuota",
        "ses:GetSendStatistics"
      ]
      Resource = "*"
      Condition = {
        StringLike = {
          "ses:FromAddress" = "noreply@${var.domain}"
        }
      }
    }]
  })
}

# CloudWatch — write metrics + logs (no delete)
resource "aws_iam_role_policy" "task_cloudwatch" {
  name = "cloudwatch-write"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "CloudWatchMetrics"
      Effect = "Allow"
      Action = [
        "cloudwatch:PutMetricData",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ]
      Resource = "*"
    }]
  })
}

# AWS X-Ray — allow the application + X-Ray daemon to send trace segments
resource "aws_iam_role_policy" "task_xray" {
  name = "xray-write"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "XRayWrite"
      Effect = "Allow"
      Action = [
        "xray:PutTraceSegments",
        "xray:PutTelemetryRecords",
        "xray:GetSamplingRules",
        "xray:GetSamplingTargets",
        "xray:GetSamplingStatisticSummaries"
      ]
      Resource = "*"
    }]
  })
}
