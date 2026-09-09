# TLS через cert-manager (опционально, по умолчанию выключено)

**Текущее состояние стенда — чистый HTTP.** `deploy-kind.sh` не ставит cert-manager,
Ingress'ы (`Helm/templates/http-ingress.yaml`) не имеют tls-секций, браузер ходит на
`http://myapp.localtest.me`. Всё, что описано ниже, живёт за флагом `tls.enabled`
(`Helm/values.yaml`), который по умолчанию `false`: при выключенном флаге чарт
рендерится ровно так же, как до появления этого документа — ни tls-секций, ни
аннотаций, ни новых ресурсов.

Этот документ — про то, как включить TLS, что при этом сломается, и как вернуться.

## Что появляется при включении

Скриптом (до helm, см. «Порядок» ниже):

- **cert-manager** версии `CERT_MANAGER_VERSION` (по умолчанию `v1.21.1`) — сырым
  манифестом релиза, как и ingress-nginx. Три деплоймента в namespace `cert-manager`:
  контроллер, `cert-manager-webhook`, `cert-manager-cainjector`.

Чартом (`Helm/templates/tls-cert-manager.yaml`, целиком под `if .Values.tls.enabled`):

1. `ClusterIssuer hybrid-selfsigned` — самоподписанный bootstrap-издатель;
2. `Certificate hybrid-ca` в namespace **`cert-manager`** — внутренний CA стенда
   (секрет `hybrid-ca`);
3. `ClusterIssuer hybrid-ca-issuer` — рабочий издатель, подписывает ключом этого CA;
4. `Certificate hybrid-ingress-tls` в namespace релиза — сертификат на
   `ingress.host`, секрет `hybrid-tls`.

Плюс в обоих Ingress'ах появляются `spec.tls` со ссылкой на `hybrid-tls` и аннотация
`nginx.ingress.kubernetes.io/ssl-redirect`.

Почему самоподписанный CA, а не Let's Encrypt: ACME-челлендж требует, чтобы CA достучался
до хоста снаружи, а `myapp.localtest.me` резолвится в `127.0.0.1` и наружу не смотрит. Для
реального домена переключатель оставлен: `tls.issuer.mode=existing` +
`tls.issuer.existing.name/kind` — тогда чарт не создаёт ни Issuer'ов, ни CA, а только
`Certificate` со ссылкой на готовый издатель (ACME/Vault/чей угодно). На kind этот режим
не проверялся.

### Почему CA-сертификат в namespace `cert-manager`, а не релиза

`CA ClusterIssuer` — ресурс кластерного скоупа, и секрет своего CA он ищет в
«cluster resource namespace» cert-manager'а, то есть в его собственном namespace.
Секрет, лежащий в namespace релиза, он просто не увидит. Отсюда явный
`namespace: {{ tls.certManagerNamespace }}` у `Certificate hybrid-ca`.

### Порядок: cert-manager ставится ДО helm

CRD `Certificate`/`ClusterIssuer` обязаны существовать в кластере в момент, когда helm
применяет `tls-cert-manager.yaml`. Зависимостью чарта это не решается: внутри одного
релиза helm ставит CRD зависимости и ресурсы умбреллы одним махом, и apply падает с
`no matches for kind "ClusterIssuer"`. Поэтому cert-manager ставит `deploy-kind.sh`
перед `helm upgrade` и ждёт готовности всех трёх деплойментов **и** появления endpoint'а
вебхука (иначе первый же `Certificate` упирается в неподнятый вебхук — та же гонка, из-за
которой в скрипте уже есть отдельное ожидание admission-вебхука ingress-nginx).

## Что сломается — прочитать до включения

1. **Вход через Google.** Redirect URI зарегистрирован в консоли Google на `http://`.
   Как только nginx начнёт редиректить на HTTPS, OAuth-флоу отвалится, и чинится это
   в консоли Google, а не в репозитории. Либо заранее добавьте `https://`-вариант
   redirect URI, либо включайте TLS с `tls.sslRedirect=false`.
2. **`ssl-redirect` включается сам.** Как только у Ingress появляется tls-секция,
   ingress-nginx начинает редиректить HTTP→HTTPS на **всех** путях этого Ingress'а.
   То есть tls — не аддитивное изменение: меняется поведение обоих Ingress'ов разом,
   включая SockJS-хендшейк и `auth_request` к `/jwtcheck`. Значение вынесено в
   `tls.sslRedirect`, чтобы решение было явным; `false` оставляет HTTP рабочим
   параллельно с HTTPS.
3. **Браузер не знает наш CA.** До импорта корня в доверенные хранилища каждый запрос
   упирается в предупреждение, а `fetch`/SockJS со страницы падают без внятной ошибки
   (страница просто не работает, консоль показывает сетевую ошибку, а не «bad cert»).

## Как включить

```bash
TLS_ENABLED=true ./deploy-kind.sh
```

Скрипт поставит cert-manager, дождётся его и добавит `--set tls.enabled=true` к
`helm upgrade`. Ручной эквивалент (если cert-manager уже стоит):

```bash
helm upgrade --install hybrid ./Helm -n hybrid-platform --set tls.enabled=true  # + остальные --set из скрипта
```

Проверка выпуска:

```bash
kubectl get clusterissuer
kubectl -n cert-manager get certificate hybrid-ca
kubectl -n hybrid-platform get certificate hybrid-ingress-tls
kubectl -n hybrid-platform get secret hybrid-tls
curl -kIv https://myapp.localtest.me/          # -k: пока CA не импортирован
```

`Certificate` может секунду-другую побыть `READY=False`: helm применяет `Certificate`
раньше `ClusterIssuer` (сортировка манифестов по kind), да и `hybrid-ca-issuer` в любом
случае становится готов только после выпуска CA. cert-manager дожимает это сам, когда
издатель становится Ready.

## Импорт CA в доверенные (чтобы браузер перестал ругаться)

```bash
kubectl -n cert-manager get secret hybrid-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > /tmp/hybrid-ca.crt
# macOS (потребует пароль администратора):
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain /tmp/hybrid-ca.crt
```

Firefox использует собственное хранилище: `Settings → Privacy & Security → Certificates →
View Certificates → Authorities → Import`, отметить «Trust this CA to identify websites».

Секрет `hybrid-ca` переживает `helm uninstall` (cert-manager не удаляет секреты вслед за
`Certificate`), поэтому переустановка релиза переиспользует тот же CA и импортированный
корень остаётся валидным. Если CA всё-таки удалили — импорт придётся повторить.

Удалить корень с macOS: `sudo security delete-certificate -c hybrid-local-ca
/Library/Keychains/System.keychain`.

## Проверка авторотации

Тикет просит проверить именно авто-обновление, а не только первый выпуск. Ждать 75 суток
не нужно — сроки задаются в values, а cert-manager перевыпускает сертификат за
`renewBefore` до конца `duration`:

```bash
helm upgrade --install hybrid ./Helm -n hybrid-platform \
  --set tls.enabled=true \
  --set tls.certificate.duration=1h \
  --set tls.certificate.renewBefore=55m   # обновление примерно через 5 минут после выпуска
```

Минимальный `duration`, который принимает cert-manager, — `1h`; `renewBefore` обязан быть
меньше `duration`.

Что смотреть:

```bash
# Renewal Time должен быть ~5 минут от Not After минус 55m; после ротации оба сдвигаются
kubectl -n hybrid-platform get certificate hybrid-ingress-tls \
  -o jsonpath='{.status.notBefore} {.status.notAfter} {.status.renewalTime}{"\n"}'

# Каждая ротация = новый CertificateRequest (revision растёт), старые остаются в истории
kubectl -n hybrid-platform get certificaterequest -w

# События самого Certificate: Issuing -> Requested -> Issued
kubectl -n hybrid-platform describe certificate hybrid-ingress-tls | tail -20

# Лог контроллера про перевыпуск
kubectl -n cert-manager logs deploy/cert-manager -f | grep -i -e renew -e issuing -e hybrid-ingress-tls

# Содержимое секрета реально поменялось: serial и даты другие, ключ другой
# (в Certificate стоит privateKey.rotationPolicy: Always)
kubectl -n hybrid-platform get secret hybrid-tls -o jsonpath='{.data.tls\.crt}' \
  | base64 -d | openssl x509 -noout -serial -dates
```

Признак успеха: через ~5 минут после выпуска в namespace появляется новый
`CertificateRequest` (`REVISION` +1), `status.notAfter`/`renewalTime` у `Certificate`
сдвигаются вперёд, serial в секрете меняется, под ingress-nginx подхватывает новый
секрет без рестарта. После проверки верните нормальные сроки (`2160h`/`360h`) — иначе
контроллер будет крутить перевыпуск каждый час.

Отдельно можно форсировать ротацию без ожидания:
`kubectl -n hybrid-platform cert-manager renew hybrid-ingress-tls` (плагин kubectl
cert-manager) или удалив секрет `hybrid-tls` — но это проверяет выпуск, а не авторотацию
по срокам, поэтому как доказательство ротации не годится.

## Как вернуться на HTTP

```bash
./deploy-kind.sh            # TLS_ENABLED не задан -> tls.enabled=false
```

`helm upgrade` уберёт tls-секции, аннотации `ssl-redirect`, оба `ClusterIssuer`'а и оба
`Certificate`. Останутся: сам cert-manager (удаляется отдельно —
`kubectl delete -f https://github.com/cert-manager/cert-manager/releases/download/v1.21.1/cert-manager.yaml`,
операция деструктивная и снесёт CRD вместе со всеми Certificate в кластере), секрет
`hybrid-ca` в namespace `cert-manager` и секрет `hybrid-tls` в namespace релиза
(безвредны, но если хочется чисто — удалить руками). Браузер может ещё какое-то время
помнить HSTS/редирект на HTTPS — лечится очисткой данных сайта.

## Ссылки

- `Helm/values.yaml`, секция `tls` — все параметры с комментариями.
- `Helm/templates/tls-cert-manager.yaml` — цепочка издателей и сертификатов.
- `Helm/templates/http-ingress.yaml` — tls-секции и `ssl-redirect` под флагом.
- `deploy-kind.sh` — установка cert-manager и ожидание готовности.
