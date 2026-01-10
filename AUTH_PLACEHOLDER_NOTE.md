# Placeholders to update

Перед запуском замени заглушки в конфиге:

## AuthService
- `AuthService/src/main/resources/application.yml`
  - `spring.security.oauth2.client.registration.google.client-id`: CHANGE_ME
  - `spring.security.oauth2.client.registration.google.client-secret`: CHANGE_ME

## HTTPService
- `HTTPService/src/main/resources/application.yml`
  - `securityProps.refresh-secret`: CHANGE_ME
