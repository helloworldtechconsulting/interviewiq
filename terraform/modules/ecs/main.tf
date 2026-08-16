resource "aws_ecs_cluster" "main" {
  name = "${var.project}-${var.env}"

  setting {
    name  = "containerInsights"
    value = var.env == "prod" ? "enabled" : "disabled"
  }

  tags = var.tags
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = var.env == "prod" ? "FARGATE" : "FARGATE_SPOT"
    weight            = 1
    base              = var.env == "prod" ? 1 : 0
  }
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${var.project}/${var.env}/backend"
  retention_in_days = var.env == "prod" ? 90 : 14
  kms_key_id        = var.kms_key_arn
  tags              = var.tags
}

resource "aws_security_group" "ecs_tasks" {
  name        = "${var.project}-${var.env}-ecs-sg"
  description = "Spring Boot ECS tasks"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [var.alb_security_group_id]
  }

  egress {
    description = "All outbound (NAT Gateway filters to internet; VPC endpoints for AWS services)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.project}-${var.env}-ecs-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${var.project}-${var.env}-backend"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = var.task_execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([
    {
      name      = "xray-daemon"
      image     = "public.ecr.aws/xray/aws-xray-daemon:3.x"
      essential = false

      portMappings = [{
        containerPort = 2000
        protocol      = "udp"
      }]

      environment = [
        { name = "AWS_REGION", value = var.region }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "xray"
        }
      }

      cpu    = 32
      memory = 64

      readonlyRootFilesystem = false
    },

    {
      name      = "backend"
      image     = "${var.ecr_repository_url}:${var.image_tag}"
      essential = true

      portMappings = [{
        containerPort = 8080
        protocol      = "tcp"
      }]

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = var.env == "dev" ? "local" : "prod" },
        { name = "SERVER_PORT",            value = "8080" },
        { name = "DB_HOST",                value = var.db_host },
        { name = "DB_PORT",                value = "5432" },
        { name = "DB_NAME",                value = var.db_name },
        { name = "DB_USERNAME",            value = var.db_username },
        { name = "AWS_REGION",             value = var.region },
        { name = "AWS_S3_BUCKET",          value = var.s3_bucket_name },
        { name = "APP_FRONTEND_BASE_URL",  value = "https://${var.domain}" },
        { name = "APP_BILLING_SESSION_COST_PAISE", value = tostring(var.session_cost_paise) },
        { name = "AWS_XRAY_DAEMON_ADDRESS",  value = "127.0.0.1:2000" },
        { name = "AWS_XRAY_CONTEXT_MISSING", value = "LOG_ERROR" },
        {
          name  = "JAVA_OPTS"
          value = "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom -Dspring.threads.virtual.enabled=true"
        }
      ]

      secrets = [
        { name = "DB_PASSWORD",                          valueFrom = "${var.db_password_secret_arn}:::" },
        { name = "APP_SECURITY_JWT_PRIVATE_KEY_PEM",     valueFrom = "${var.jwt_keys_secret_arn}:private_key_pem::" },
        { name = "APP_SECURITY_JWT_PUBLIC_KEY_PEM",      valueFrom = "${var.jwt_keys_secret_arn}:public_key_pem::" },
        { name = "APP_SECURITY_INVITE_SECRET",           valueFrom = "${var.invite_secret_arn}:::" },
        { name = "OPENAI_API_KEY",                       valueFrom = "${var.openai_secret_arn}:::" },
        { name = "RAZORPAY_KEY_ID",                      valueFrom = "${var.razorpay_secret_arn}:key_id::" },
        { name = "RAZORPAY_KEY_SECRET",                  valueFrom = "${var.razorpay_secret_arn}:key_secret::" }
      ]

      healthCheck = {
        command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 10
        retries     = 3
        startPeriod = 60
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "backend"
        }
      }

      ulimits = [{
        name      = "nofile"
        softLimit = 65536
        hardLimit = 65536
      }]

      readonlyRootFilesystem = false
      user                   = "1000"
    }
  ])

  ephemeral_storage {
    size_in_gib = 21
  }

  tags = var.tags
}

resource "aws_ecs_service" "backend" {
  name                               = "${var.project}-${var.env}-backend"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.backend.arn
  desired_count                      = var.desired_count
  launch_type                        = null
  platform_version                   = "LATEST"
  health_check_grace_period_seconds  = 90

  enable_execute_command = var.env != "prod"

  capacity_provider_strategy {
    capacity_provider = var.env == "prod" ? "FARGATE" : "FARGATE_SPOT"
    weight            = 100
    base              = var.env == "prod" ? var.min_tasks : 0
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "backend"
    container_port   = 8080
  }

  deployment_configuration {
    minimum_healthy_percent = var.env == "prod" ? 100 : 50
    maximum_percent         = 200

    deployment_circuit_breaker {
      enable   = true
      rollback = true
    }
  }

  lifecycle {
    ignore_changes = [
      task_definition,
      desired_count
    ]
  }

  tags = var.tags
  depends_on = [aws_cloudwatch_log_group.backend]
}

resource "aws_appautoscaling_target" "backend" {
  max_capacity       = var.max_tasks
  min_capacity       = var.min_tasks
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.backend.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.project}-${var.env}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.backend.resource_id
  scalable_dimension = aws_appautoscaling_target.backend.scalable_dimension
  service_namespace  = aws_appautoscaling_target.backend.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 70.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "memory" {
  name               = "${var.project}-${var.env}-memory-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.backend.resource_id
  scalable_dimension = aws_appautoscaling_target.backend.scalable_dimension
  service_namespace  = aws_appautoscaling_target.backend.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 80.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageMemoryUtilization"
    }
  }
}
