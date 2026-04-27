# =============================================================================
# envs/prod/terraform.tfvars
#
# NON-SENSITIVE values only. Commit this file.
# Sensitive vars (passwords, API keys) are injected by CI/CD via:
#   TF_VAR_db_password, TF_VAR_openai_api_key, etc.
# =============================================================================

region   = "ap-south-1"
domain   = "interviewiq.ai"           # CHANGE: your actual domain
vpc_cidr = "10.0.0.0/16"
image_tag = "latest"                  # overridden by CI with git SHA

alert_emails = [
  "alerts@interviewiq.ai",            # CHANGE: your ops email
]
