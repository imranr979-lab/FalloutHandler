# SSP Java Platform

Cloud-native Java 21 + Spring Boot 3 microservices for number porting management,
deployed on AWS EKS with Kubernetes, Twilio SMS notifications.

> **All company names are fictitious.** NexaTel = wireless carrier, ClearPort = OMS clearinghouse, OrderFlow = BSS/OSS system.

---

## Architecture

```
OrderFlow Systems (BSS/OSS)
        │ REST API (porting request)
        ▼
[AWS ALB Ingress — TLS, WAF]
        │
        ▼
[Spring Cloud Gateway — port 8080]
        │ JWT validation, rate limiting, circuit breakers
        │ Internal Kubernetes DNS routing
        │
        ├─► porting-service            :8081  (DynamoDB + SQS publish)
        ├─► notification-service       :8082  (Twilio SMS + SQS consume)
        ├─► fallout-service            :8083  (DynamoDB + SQS publish)
        ├─► market-validation-service  :8084  (DynamoDB + Redis cache)
        └─► auth-service               :8085  (JWT + AWS Cognito)

[All services → DynamoDB tables (per service)]
[notification-service → Twilio REST API]
[All services → AWS SQS events queue]
[EKS → CloudWatch metrics + logs]
```

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (Virtual Threads) |
| Framework | Spring Boot 3.3 |
| API Gateway | Spring Cloud Gateway 2023.0 |
| Database | AWS DynamoDB (Enhanced client v2) |
| Messaging | AWS SQS (long-polling consumer) |
| SMS | Twilio SDK 10.x |
| Security | Spring Security 6 + JWT |
| Container | Docker (multi-stage, Eclipse Temurin 21) |
| Orchestration | Kubernetes (AWS EKS) |
| Service mesh | K8s NetworkPolicy + IRSA |
| Config management | Kustomize overlays |
| Secrets | AWS Secrets Manager + External Secrets Operator |
| Caching | Redis (ElastiCache) |
| Resilience | Resilience4j (circuit breaker, retry, rate limit) |
| Build | Maven 3.9 |
| CI/CD | GitHub Actions |
| Observability | CloudWatch + Prometheus + Grafana |

---

## Two-Call SMS Flow

```
┌─────────────────────────────────────────────────────────────┐
│  FIRST CALL                                                  │
│  POST /api/v1/notifications/resolve-tn                      │
│  Input:  { "portedTn": "2025550147" }                       │
│  Output: { "portedTn": "2025550147",                        │
│            "accountHolderTn": "7035550199" }                │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  SECOND CALL                                                 │
│  POST /api/v1/notifications/sms                             │
│  Input:  {                                                   │
│    "portedTn":       "2025550147",                          │
│    "recipientTn":    "7035550199",  ← from first call       │
│    "event":          "FALLOUT_DETECTED",                    │
│    "marketCode":     "DC-001",                              │
│    "falloutMessage": "Account number mismatch",             │
│    "frequency":      { intervalMinutes: 60, maxAttempts: 3 }│
│  }                                                           │
│  Output: { twilioMessageSid: "SM...", status: "queued" }    │
└─────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
ssp-java-platform/
├── pom.xml                              Parent Maven POM (Java 21, Spring Boot 3.3)
├── shared/
│   └── common-models/                   Shared DTOs, enums, events
│       └── src/main/java/com/nexatel/ssp/common/
│           ├── dto/                     ApiResponse, PortingRequestDto, FalloutDto
│           ├── enums/                   PortingStatus, FalloutType, NotificationEvent
│           └── event/                   PortingEvent (SQS domain events)
│
├── services/
│   ├── api-gateway/                     Spring Cloud Gateway (port 8080)
│   │   └── src/main/resources/
│   │       └── application.yml          Route config, circuit breakers, rate limiting
│   │
│   ├── porting-service/                 Number porting CRUD (port 8081)
│   │   └── src/main/java/com/nexatel/ssp/porting/
│   │       ├── PortingServiceApplication.java
│   │       ├── entity/PortingRequest.java           DynamoDB entity (IRSA access)
│   │       ├── repository/PortingRequestRepository.java
│   │       ├── service/PortingService.java           Business logic + ClearPort integration
│   │       ├── controller/PortingController.java     REST endpoints
│   │       └── messaging/PortingEventPublisher.java  SQS publisher
│   │
│   ├── notification-service/            Twilio SMS gateway (port 8082)
│   │   └── src/main/java/com/nexatel/ssp/notification/
│   │       ├── service/TwilioSmsService.java         Two-call SMS flow
│   │       ├── template/SmsTemplateEngine.java       Standard/fallout message templates
│   │       └── messaging/PortingEventConsumer.java   SQS consumer → auto-SMS
│   │
│   ├── fallout-service/                 Fallout management (port 8083)
│   │   └── src/main/java/com/nexatel/ssp/fallout/
│   │       └── service/FalloutService.java           PIN/PW, Account#, ZIP resolution
│   │
│   ├── market-validation-service/       NPA/NXX validation (port 8084)
│   │   └── src/main/java/com/nexatel/ssp/market/
│   │       └── service/MarketValidationService.java  In/out of market determination
│   │
│   └── auth-service/                    JWT + Cognito (port 8085)
│
├── k8s/
│   ├── base/
│   │   ├── deployments/                 K8s Deployments (HPA, PDB, topology spread)
│   │   ├── configmaps/                  ssp-config.yaml + ExternalSecrets
│   │   └── namespace-and-rbac.yaml      NS, NetworkPolicy, ServiceAccounts (IRSA)
│   └── overlays/
│       ├── dev/                         1 replica, debug logging
│       ├── staging/                     2 replicas, integration test config
│       └── prod/                        3+ replicas, prod table names, WARN log
│
├── .github/workflows/
│   └── deploy.yml                       Full CI/CD: build → test → scan → K8s deploy
└── docs/
```

---

## Local Development

### Prerequisites
```bash
# Required
java --version   # Java 21
mvn --version    # Maven 3.9+
docker --version
kubectl version --client
kustomize version

# AWS CLI
aws --version

# Optional — for local DynamoDB/SQS
docker-compose up -d localstack
```

### Quick Start
```bash
# 1. Clone
git clone <repo>
cd ssp-java-platform

# 2. Install + compile all modules
mvn install -DskipTests

# 3. Start services locally (LocalStack for AWS)
docker-compose up -d

# 4. Run porting service
cd services/porting-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 5. Test the API
curl -X POST http://localhost:8081/api/v1/port \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "portedTn": "2025550147",
    "customerId": "CUST-001",
    "orderId": "ORD-2026-001",
    "marketCode": "DC-001"
  }'
```

---

## Deployment Pipeline

### Stage 1 — Development (auto on `main` push)

```
git push origin main
  ↓
GitHub Actions:
  1. mvn compile (Java 21, all modules)
  2. Checkstyle + SpotBugs
  3. mvn test (JaCoCo coverage ≥ 80%)
  4. OWASP dependency check + Snyk
  5. docker build (multi-stage, Eclipse Temurin 21)
  6. Trivy image scan (block on HIGH/CRITICAL CVEs)
  7. Push to ECR
  8. kubectl apply -k k8s/overlays/dev
  9. kubectl rollout status (all 6 deployments)
  10. Smoke tests → Slack notification
```

### Stage 2 — Staging (auto on `release/*` branch)

```bash
git checkout -b release/2.1.0
git push origin release/2.1.0

# Pipeline runs:
#   kubectl apply -k k8s/overlays/staging
#   mvn verify -P staging-tests
#   k6 load test (p99 < 2s at 200 RPS)
#   Slack notification with prod approval link
```

### Stage 3 — Production (requires 2 approvers)

```bash
# Create GitHub Release (triggers prod pipeline)
gh release create v2.1.0 --title "Release 2.1.0"

# Pipeline pauses at GitHub Environment:
#   - 2 required reviewers: [eng-lead, ops-lead]
#   - 15-minute wait timer (rollback window)
#   - After approval:
#     kubectl apply -k k8s/overlays/prod
#     kubectl rollout status (each service, 15-min timeout)
#     Health checks on all endpoints
#     CloudWatch alarm check (5 min post-deploy)
#     Slack confirmation
```

---

## Kubernetes Operations

### EKS Cluster Setup
```bash
# Create EKS cluster
eksctl create cluster \
  --name ssp-eks-prod \
  --version 1.30 \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type m5.large \
  --nodes 3 \
  --nodes-min 2 \
  --nodes-max 10 \
  --with-oidc \
  --ssh-access \
  --managed

# Install AWS Load Balancer Controller
helm repo add eks https://aws.github.io/eks-charts
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=ssp-eks-prod

# Install External Secrets Operator
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace

# Install metrics-server (for HPA)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### Deploy manually
```bash
# DEV
kubectl apply -k k8s/overlays/dev

# STAGING
kubectl apply -k k8s/overlays/staging

# PRODUCTION
kubectl apply -k k8s/overlays/prod

# Watch rollout
kubectl rollout status deployment/porting-service -n ssp -w

# Check all pods
kubectl get pods -n ssp -l tier=service

# Scale manually (emergency)
kubectl scale deployment porting-service --replicas=5 -n ssp
```

### Rollback
```bash
# Rollback all services
kubectl rollout undo deployment --all -n ssp

# Rollback specific service
kubectl rollout undo deployment/porting-service -n ssp

# Check rollout history
kubectl rollout history deployment/porting-service -n ssp

# Rollback to specific revision
kubectl rollout undo deployment/porting-service --to-revision=3 -n ssp
```

---

## Twilio Configuration

1. Log in to [Twilio Console](https://console.twilio.com)
2. Create a Messaging Service
3. Add a phone number to the service
4. Store credentials in AWS Secrets Manager:
   ```bash
   aws secretsmanager create-secret \
     --name "ssp/twilio" \
     --secret-string '{
       "accountSid": "ACxxxxxxxxxx",
       "authToken":  "your_auth_token",
       "fromNumber": "+12025550001"
     }'
   ```
5. Configure webhook: `https://ssp.nexatel-internal.example.com/api/v1/notifications/webhook/twilio`
6. ExternalSecret in `k8s/base/configmaps/ssp-config.yaml` will sync automatically

---

## Maven Commands

```bash
# Build all
mvn clean install

# Build single service
mvn clean install -pl services/porting-service -am

# Run tests
mvn test

# Run with coverage
mvn verify

# Skip tests (CI/CD fast build)
mvn package -DskipTests

# Run locally
mvn spring-boot:run -pl services/porting-service \
  -Dspring-boot.run.profiles=local

# Build Docker image (Buildpacks)
mvn spring-boot:build-image \
  -pl services/porting-service \
  -DECR_REGISTRY=ACCOUNT.dkr.ecr.us-east-1.amazonaws.com
```

---

## Observability

| Tool | Purpose | URL |
|---|---|---|
| CloudWatch Logs | Structured JSON logs | AWS Console |
| CloudWatch Metrics | JVM, HTTP, DynamoDB, SQS | AWS Console |
| Prometheus | `/actuator/prometheus` per service | Internal |
| Grafana | Dashboards (JVM, HTTP, porting) | `http://grafana.monitoring:3000` |
| Spring Boot Actuator | Health, info, metrics | `/actuator/health` |

### CloudWatch Alarms (auto-created by CDK/Terraform)
- `ssp-api-5xx-rate-prod` — >1% 5xx errors in 5 min
- `ssp-pod-crash-prod` — any pod CrashLoopBackOff
- `ssp-sqs-dlq-messages` — any message in DLQ
- `ssp-dynamodb-errors` — DynamoDB error rate

---

