# default

Part of [Auth Schemes](index.md).

## getWithHeaderKey

`GET /header-key`

Authentication: `headerKey`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/header-key'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/header-key', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/header-key',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/header-key"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## getWithQueryKey

`GET /query-key`

Authentication: `queryKey`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/query-key'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/query-key', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/query-key',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/query-key"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## getWithBasic

`GET /basic`

Authentication: `basicAuth`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/basic'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/basic', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/basic',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/basic"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## getWithOAuth

`GET /oauth`

Authentication: `oauth`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/oauth'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/oauth', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/oauth',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/oauth"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## getPublic

`GET /public`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/public'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/public', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/public',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/public"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## getInherited

`GET /inherited`

Authentication: `bearerAuth`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/inherited'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/inherited', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/inherited',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/inherited"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```
