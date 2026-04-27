output "kms_key_arn"           { value = aws_kms_key.secrets.arn }
output "db_password_arn"       { value = aws_secretsmanager_secret.db_password.arn }
output "jwt_keys_arn"          { value = aws_secretsmanager_secret.jwt_keys.arn }
output "invite_secret_arn"     { value = aws_secretsmanager_secret.invite_secret.arn }
output "openai_arn"            { value = aws_secretsmanager_secret.openai.arn }
output "razorpay_arn"          { value = aws_secretsmanager_secret.razorpay.arn }
