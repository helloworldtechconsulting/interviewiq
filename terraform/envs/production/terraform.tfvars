gcp_project_id = "your-gcp-project-id"
gcp_region     = "asia-south1"

vpc_cidr    = "10.0.0.0/16"
subnet_cidr = "10.0.1.0/24"

database_instance_name = "interviewiq-postgres-production"
database_name          = "interviewiq"
database_user          = "interviewiq"
db_password            = "CHANGE_ME_TO_STRONG_PASSWORD"

data_bucket_name     = "interviewiq-production-data"
frontend_bucket_name = "interviewiq-production-frontend"

openai_api_key            = "sk-YOUR_OPENAI_KEY_HERE"
razorpay_key_id           = "rzp_live_YOUR_KEY_HERE"
razorpay_key_secret       = "YOUR_RAZORPAY_SECRET_HERE"
razorpay_webhook_secret   = "YOUR_WEBHOOK_SECRET_HERE"
jwt_secret                = "production-jwt-secret-key-use-strong-secure-key"

cloud_run_service_name   = "interviewiq-backend-production"
backend_image_url        = "asia-south1-docker.pkg.dev/your-project-id/interviewiq-docker/backend:latest"
artifact_registry_repository = "interviewiq-docker"

notification_email_address = "devops@example.com"
