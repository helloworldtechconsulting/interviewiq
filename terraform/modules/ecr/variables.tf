variable "project"             { type = string }
variable "kms_key_arn"        { type = string }
variable "allowed_account_ids"{ type = list(string); default = [] }
variable "tags"               { type = map(string); default = {} }
