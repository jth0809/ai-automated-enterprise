# 백엔드 API 제약 사항

장애가 발생해도 시스템 전체에 영향을 주지 않도록(Fail gracefully) 복원력 있는 마이크로서비스를 설계합니다.
강한 결합(Tight coupling)을 방지하기 위해 비동기 이벤트 기반 패턴을 채택합니다.
애플리케이션 계층에서 권한 부여를 검증하여 제로 트러스트 원칙을 구현합니다.
모든 API 계약(Contracts)에 대해 엄격한 하위 호환성(Backward compatibility)을 유지합니다.
