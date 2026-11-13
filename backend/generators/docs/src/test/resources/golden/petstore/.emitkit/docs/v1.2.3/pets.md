# pets

Part of [Petstore](index.md).

## listPets

`GET /pets`

### Parameters

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| limit | query | integer (int32) | No |  |

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok | array of Pet |

**200 response fields**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| id | integer (int64) | Yes |  |
| name | string | Yes |  |
| tag | string | No |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/pets'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/pets', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/pets',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/pets"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## createPet

`POST /pets`

### Request body

Content type `application/json`. Required: Yes. Schema: Pet.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| id | integer (int64) | Yes |  |
| name | string | Yes |  |
| tag | string | No |  |

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 201 | created |  |

### Examples

#### curl

```sh
curl -X POST 'https://api.example.com/pets' \
  -H 'Content-Type: application/json' \
  -d '{"id":1,"name":"string","tag":"string"}'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/pets', {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: '{"id":1,"name":"string","tag":"string"}',
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "POST",
    'https://api.example.com/pets',
    headers={"Content-Type": "application/json"},
    data='{"id":1,"name":"string","tag":"string"}',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/pets"))
    .header("Content-Type", "application/json")
    .method("POST", HttpRequest.BodyPublishers.ofString("{\"id\":1,\"name\":\"string\",\"tag\":\"string\"}"))
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## getPetById

`GET /pets/{petId}`

### Parameters

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| petId | path | integer (int64) | Yes |  |

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok | Pet |
| 404 | not found |  |

**200 response fields**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| id | integer (int64) | Yes |  |
| name | string | Yes |  |
| tag | string | No |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/pets/1'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/pets/1', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/pets/1',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/pets/1"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```
