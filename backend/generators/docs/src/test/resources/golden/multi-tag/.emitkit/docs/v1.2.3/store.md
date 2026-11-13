# store

Part of [Multi Tag](index.md).

## listStoreOrders

`GET /store/orders`

### Responses

| Status | Description | Schema |
| --- | --- | --- |
| 200 | ok |  |

### Examples

#### curl

```sh
curl -X GET 'https://api.example.com/store/orders'
```

#### TypeScript

```ts
const response = await fetch('https://api.example.com/store/orders', {
  method: "GET",
});
console.log(response.status, await response.text());
```

#### Python

```python
import requests

response = requests.request(
    "GET",
    'https://api.example.com/store/orders',
)
print(response.status_code, response.text)
```

#### Java

```java
HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.example.com/store/orders"))
    .method("GET", HttpRequest.BodyPublishers.noBody())
    .build();
HttpResponse<String> response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```
