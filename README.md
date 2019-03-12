# WebSocketProxy

## build
```
$ mvn clean compile assembly:single
```
## run
```
$ java -jar target/WebSocketProxy-1.0-jar-with-dependencies.jar -u wss://echo.websocket.org -p 9000
$ curl --data 'hello%0a' 127.0.0.1:9000
hello
```
