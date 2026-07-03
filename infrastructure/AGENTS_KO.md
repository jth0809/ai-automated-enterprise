# 인프라 규칙: Terraform 및 Ansible

인프라를 오직 코드(IaC)로만 취급합니다.
복잡한 클라우드 설정을 추상화하여 개발자에게 단순하고 닦여진 길(Paved paths)을 제공합니다.
잘못된 구성을 방지하기 위해 모든 인프라 변경 사항을 자동으로 검증합니다.
가능한 한 상태(State)를 일관되고 불변하게 유지합니다.
엄격한 제한: Oracle Always Free 한도(ARM 인스턴스 최대 2 OCPU/12GB RAM, 블록 스토리지 200GB) 내에서만 프로비저닝해야 합니다.
엄격한 제한: 무료 티어 유지를 위해 Oracle Autonomous Database (ATP)만 사용해야 합니다.
