# Build AMI with Packer

This guide shows you how to create an Amazon Machine Image (AMI) containing your Java Maven Template application using HashiCorp Packer.

## Prerequisites

- Packer >= 1.9.0 installed
- AWS account with EC2 access
- AWS credentials configured

## Steps

### 1. Navigate to Packer Directory

```bash
cd docs/infrastructure/packer/aws
```

### 2. Initialize Packer

```bash
packer init .
```

### 3. Configure Variables

Create `variables.pkrvars.hcl`:

```hcl
aws_region    = "us-east-1"
instance_type = "t3.medium"
ssh_username  = "ec2-user"
# Source AMI for Amazon Linux 2023
source_ami    = "ami-0c7217cdde317cfec"
```

### 4. Validate Configuration

```bash
packer validate -var-file=variables.pkrvars.hcl java-maven-ami.pkr.hcl
```

### 5. Build AMI

```bash
packer build -var-file=variables.pkrvars.hcl java-maven-ami.pkr.hcl
```

### 6. Note the AMI ID

Output will include:
```
==> amazon-ebs: AMI: ami-xxxxxxxxxxxxxxxxx
```

Use this AMI ID in your Terraform configuration.

## Customization

### Add Custom Provisioners

Edit `java-maven-ami.pkr.hcl` to add custom scripts:

```hcl
build {
  sources = ["source.amazon-ebs.java-maven"]

  provisioner "shell" {
    inline = [
      "sudo yum update -y",
      "sudo yum install -y java-21-openjdk",
    ]
  }

  provisioner "file" {
    source      = "../../../target/jotp-1.0.0-SNAPSHOT.jar"
    destination = "/tmp/app.jar"
  }

  provisioner "shell" {
    inline = [
      "sudo mkdir -p /opt/app",
      "sudo mv /tmp/app.jar /opt/app/",
      "sudo chmod 644 /opt/app/app.jar",
    ]
  }
}
```

### Use Different Base Image

Common base AMIs:

| OS | AMI Pattern |
|----|-------------|
| Amazon Linux 2023 | `ami-0c7217cdde317cfec` (us-east-1) |
| Ubuntu 22.04 LTS | Search Ubuntu Cloud Image Finder |
| RHEL 9 | Search AWS Marketplace |

### Add Systemd Service

```hcl
provisioner "shell" {
  inline = [
    "sudo tee /etc/systemd/system/java-maven-app.service > /dev/null <<EOF",
    "[Unit]",
    "Description=Java Maven Template Application",
    "After=network.target",
    "",
    "[Service]",
    "Type=simple",
    "ExecStart=/usr/bin/java -jar /opt/app/app.jar",
    "Restart=always",
    "",
    "[Install]",
    "WantedBy=multi-user.target",
    "EOF",
    "sudo systemctl enable java-maven-app",
  ]
}
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `VpcIdDoesNotExist` | Specify valid VPC and subnet |
| `InvalidAMIID.NotFound` | Verify source AMI exists in region |
| `SSH timeout` | Check security group allows SSH |

## Next Steps

- [Deploy to AWS](deploy-to-aws.md) - Use your AMI in Terraform
- [Configure CI/CD](configure-ci-cd.md) - Automate AMI builds

## Related Resources

- [Packer Amazon Builder](https://developer.hashicorp.com/packer/plugins/builders/amazon)
- [AWS AMI Documentation](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AMIs.html)
