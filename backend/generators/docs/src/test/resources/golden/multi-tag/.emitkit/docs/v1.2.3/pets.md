# pets

Part of [Multi Tag](index.md).

## getPetInventory

`GET /pets/{id}/inventory`

### Parameters

| Name | In | Required | Type | Description |
| --- | --- | --- | --- | --- |
| id | path | Yes | integer |  |

### Responses

| Status | Description |
| --- | --- |
| 200 | ok |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/pets/1/inventory'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/pets/1/inventory', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/pets/1/inventory',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/pets/1/inventory"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```
