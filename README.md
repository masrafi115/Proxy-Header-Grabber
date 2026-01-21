# Proxy Header Grabber

A Burp Suite extension that automatically captures HTTP headers from specified URLs observed in proxy traffic and replaces them in Repeater requests for in-scope targets.

Use case: (kinda) complex authentication / token refresh flows that are implemented in frontend -> a current Authorization token (or any other header) can be grabbed from the browser requests going through proxy and used in repeater.

Currently only proxy **requests** are implemented. Automatic using of the grabbed values is in **Repeater** only and only for in-scope URLs.

TODO:
- allow specifying response header values to grab
- use in other tools (not just Repeater)
- add toggle for in-scope only

# Install

Build in Docker:
```
docker run --rm -v "$(pwd)":/project -w /project gradle:8.5-jdk21 gradle jar
```

Then load the JAR in Burp Suite.