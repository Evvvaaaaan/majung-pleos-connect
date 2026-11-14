# 마중 중계 서버 + 동승자 웹을 한 컨테이너로. 외부 의존성 없음(Node 내장 http/crypto만 사용).
#   docker build -f relay-server/Dockerfile -t majung-relay .
#   docker run --rm -p 8080:8080 majung-relay
# 공개 HTTPS 주소(Fly.io·Render 등)로 올리면 폰 브라우저의 위치 공유까지 동작한다. 세션은 메모리에만 있으므로 인스턴스는 1개로 둔다.
FROM node:22-alpine
WORKDIR /app
COPY relay-server/server.mjs ./relay-server/server.mjs
COPY web-companion/index.html ./web-companion/index.html
ENV PORT=8080
EXPOSE 8080
USER node
CMD ["node", "relay-server/server.mjs"]
