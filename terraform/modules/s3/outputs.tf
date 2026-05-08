output "bucket_name"   { value = aws_s3_bucket.main.bucket }
output "bucket_arn"    { value = aws_s3_bucket.main.arn }
output "kms_key_arn"   { value = aws_kms_key.s3.arn }
output "kms_key_id"    { value = aws_kms_key.s3.key_id }
