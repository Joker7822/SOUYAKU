FROM node:22-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY *.js ./
ENV PORT=8787
EXPOSE 8787
CMD ["node", "server.js"]
