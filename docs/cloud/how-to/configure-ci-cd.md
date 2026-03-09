# Configure CI/CD for Cloud Deployments

This guide shows you how to set up continuous integration and deployment pipelines for your Java Maven Template application across multiple cloud providers.

## Prerequisites

- Source code in Git repository (GitHub, GitLab, etc.)
- Cloud provider accounts
- Container registry access (optional)

## GitHub Actions

### Complete CI/CD Pipeline

Create `.github/workflows/deploy.yml`:

```yaml
name: Build and Deploy

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  JAVA_VERSION: '21'
  TERRAFORM_VERSION: '1.6.0'
  PACKER_VERSION: '1.9.0'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'
          cache: maven

      - name: Build with Maven
        run: ./mvnw verify

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-jar
          path: target/*.jar

  test-infrastructure:
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/checkout@v4

      - name: Setup Terraform
        uses: hashicorp/setup-terraform@v3
        with:
          terraform_version: ${{ env.TERRAFORM_VERSION }}

      - name: Terraform Format Check
        run: terraform fmt -check -recursive docs/infrastructure/terraform/

      - name: Terraform Validate (AWS)
        run: |
          cd docs/infrastructure/terraform/aws
          terraform init -backend=false
          terraform validate

  build-image:
    runs-on: ubuntu-latest
    needs: build
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4

      - name: Download artifact
        uses: actions/download-artifact@v4
        with:
          name: app-jar
          path: target/

      - name: Setup Packer
        uses: hashicorp/setup-packer@main
        with:
          version: ${{ env.PACKER_VERSION }}

      - name: Build AMI
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
        run: |
          cd docs/infrastructure/packer/aws
          packer init .
          packer build -var "ami_name=java-maven-${{ github.sha }}" java-maven-ami.pkr.hcl

  deploy-aws:
    runs-on: ubuntu-latest
    needs: build-image
    if: github.ref == 'refs/heads/main'
    environment: production-aws
    steps:
      - uses: actions/checkout@v4

      - name: Setup Terraform
        uses: hashicorp/setup-terraform@v3
        with:
          terraform_version: ${{ env.TERRAFORM_VERSION }}

      - name: Terraform Init
        run: |
          cd docs/infrastructure/terraform/aws
          terraform init
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}

      - name: Terraform Apply
        run: |
          cd docs/infrastructure/terraform/aws
          terraform apply -auto-approve
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          TF_VAR_ami_id: ${{ needs.build-image.outputs.ami_id }}
```

### AWS Deployment Workflow

Create `.github/workflows/deploy-aws.yml`:

```yaml
name: Deploy to AWS

on:
  workflow_dispatch:
    inputs:
      environment:
        description: 'Environment'
        required: true
        default: 'staging'
        type: choice
        options:
          - staging
          - production

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}
    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-east-1

      - name: Setup Terraform
        uses: hashicorp/setup-terraform@v3

      - name: Terraform Init
        run: cd docs/infrastructure/terraform/aws && terraform init

      - name: Terraform Plan
        run: cd docs/infrastructure/terraform/aws && terraform plan -out=tfplan

      - name: Terraform Apply
        run: cd docs/infrastructure/terraform/aws && terraform apply -auto-approve tfplan
```

### Azure Deployment Workflow

Create `.github/workflows/deploy-azure.yml`:

```yaml
name: Deploy to Azure

on:
  workflow_dispatch:

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: production-azure
    steps:
      - uses: actions/checkout@v4

      - name: Azure Login
        uses: azure/login@v1
        with:
          creds: ${{ secrets.AZURE_CREDENTIALS }}

      - name: Setup Terraform
        uses: hashicorp/setup-terraform@v3

      - name: Terraform Init
        run: cd docs/infrastructure/terraform/azure && terraform init
        env:
          ARM_CLIENT_ID: ${{ secrets.ARM_CLIENT_ID }}
          ARM_CLIENT_SECRET: ${{ secrets.ARM_CLIENT_SECRET }}
          ARM_SUBSCRIPTION_ID: ${{ secrets.ARM_SUBSCRIPTION_ID }}
          ARM_TENANT_ID: ${{ secrets.ARM_TENANT_ID }}

      - name: Terraform Apply
        run: cd docs/infrastructure/terraform/azure && terraform apply -auto-approve
        env:
          ARM_CLIENT_ID: ${{ secrets.ARM_CLIENT_ID }}
          ARM_CLIENT_SECRET: ${{ secrets.ARM_CLIENT_SECRET }}
          ARM_SUBSCRIPTION_ID: ${{ secrets.ARM_SUBSCRIPTION_ID }}
          ARM_TENANT_ID: ${{ secrets.ARM_TENANT_ID }}
```

## GitLab CI

Create `.gitlab-ci.yml`:

```yaml
stages:
  - build
  - test
  - deploy

variables:
  MAVEN_CLI_OPTS: "-s .m2/settings.xml --batch-mode"
  MAVEN_OPTS: "-Dmaven.repo.local=.m2/repository"

cache:
  paths:
    - .m2/repository/

build:
  stage: build
  image: maven:3.9-eclipse-temurin-21
  script:
    - ./mvnw $MAVEN_CLI_OPTS package -Dshade
  artifacts:
    paths:
      - target/*.jar
    expire_in: 1 week

test:
  stage: test
  image: maven:3.9-eclipse-temurin-21
  script:
    - ./mvnw $MAVEN_CLI_OPTS verify
  dependencies:
    - build

deploy-aws:
  stage: deploy
  image: hashicorp/terraform:1.6
  script:
    - cd docs/infrastructure/terraform/aws
    - terraform init
    - terraform apply -auto-approve
  environment:
    name: production-aws
  when: manual
  only:
    - main
```

## Jenkins Pipeline

Create `Jenkinsfile`:

```groovy
pipeline {
    agent any

    tools {
        maven 'Maven 3.9'
        jdk 'JDK 21'
    }

    environment {
        AWS_ACCESS_KEY_ID = credentials('aws-access-key-id')
        AWS_SECRET_ACCESS_KEY = credentials('aws-secret-access-key')
    }

    stages {
        stage('Build') {
            steps {
                sh './mvnw package -Dshade'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }
        }

        stage('Test') {
            steps {
                sh './mvnw verify'
            }
        }

        stage('Terraform Plan') {
            steps {
                dir('docs/infrastructure/terraform/aws') {
                    sh 'terraform init'
                    sh 'terraform plan -out=tfplan'
                }
            }
        }

        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                dir('docs/infrastructure/terraform/aws') {
                    sh 'terraform apply -auto-approve tfplan'
                }
            }
        }
    }

    post {
        always {
            junit 'target/surefire-reports/*.xml'
        }
    }
}
```

## Required Secrets

### GitHub Actions Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key |
| `ARM_CLIENT_ID` | Azure service principal client ID |
| `ARM_CLIENT_SECRET` | Azure service principal secret |
| `ARM_SUBSCRIPTION_ID` | Azure subscription ID |
| `ARM_TENANT_ID` | Azure tenant ID |
| `GOOGLE_CREDENTIALS` | GCP service account JSON |

### Setting up Secrets

```bash
# GitHub CLI
gh secret set AWS_ACCESS_KEY_ID
gh secret set AWS_SECRET_ACCESS_KEY

# Or via GitHub UI
# Settings → Secrets and variables → Actions → New repository secret
```

## Environment Protection Rules

For production deployments, set up environment protection:

1. Go to Settings → Environments
2. Create `production-aws`, `production-azure`, etc.
3. Add required reviewers
4. Configure deployment branches

## Next Steps

- [Manage Secrets](manage-secrets.md) - Secure credential handling
- [Deploy to AWS](deploy-to-aws.md) - AWS deployment guide

## Related Resources

- [GitHub Actions Documentation](https://docs.github.com/actions)
- [Terraform Cloud](https://cloud.hashicorp.com/products/terraform)
- [GitLab CI/CD](https://docs.gitlab.com/ee/ci/)
