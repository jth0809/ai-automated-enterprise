# GitOps 및 배포 규칙

Git은 클러스터 상태를 나타내는 유일한 진실 공급원(Source of Truth)입니다.
점진적 배포(Progressive delivery)를 통해 안전한 롤아웃을 자동화하여 장애 반경을 제한합니다.
백그라운드에서 조용히 제로 트러스트(Zero-Trust) 네트워크 정책을 적용합니다.
개발자가 Kubernetes 내부 구조를 관리할 필요 없이 자신 있게 배포할 수 있도록 지원합니다.
