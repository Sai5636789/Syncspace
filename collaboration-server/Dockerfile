FROM node:22-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY src ./src
EXPOSE 3001
HEALTHCHECK --interval=10s --timeout=5s --retries=10 \
  CMD wget -q -O /dev/null http://localhost:3001/health || exit 1
CMD ["npm", "start"]
