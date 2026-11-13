# admin

Part of [Multi Tag](index.md).

## resetPets

`POST /admin/reset`

### Responses

| Status | Description |
| --- | --- |
| 200 | ok |

### Examples

#### curl

```sh
curl -X POST 'https://api.example.com/admin/reset'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/admin/reset', {
  method: "POST",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "POST",
    'https://api.example.com/admin/reset',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/admin/reset"))
    .method("POST", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```
