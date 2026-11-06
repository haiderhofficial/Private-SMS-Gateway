// Minimal example server for Private SMS Gateway.  npm i express  &&  TOKEN=secret node server.js
// App settings:  Poll URL  http://YOUR_SERVER:3000/poll
//                Result URL http://YOUR_SERVER:3000/result/{id}
//                Auth       Bearer token = TOKEN
const express = require('express');
const app = express();
app.use(express.json());

const TOKEN = process.env.TOKEN || 'change-me';
const queue = []; // { id, to, body, status }
let nextId = 1;

app.use((req, res, next) =>
  req.get('authorization') === `Bearer ${TOKEN}` ? next() : res.sendStatus(401));

// Queue a message:  curl -H "Authorization: Bearer secret" -H "Content-Type: application/json" \
//                        -d '{"to":"+923001234567","body":"hello"}' http://localhost:3000/send
app.post('/send', (req, res) => {
  const m = { id: String(nextId++), to: req.body.to, body: req.body.body, status: 'pending' };
  queue.push(m);
  res.json(m);
});

app.get('/poll', (req, res) => {
  const m = queue.find((x) => x.status === 'pending');
  if (!m) return res.sendStatus(204);
  m.status = 'sending';
  res.json({ id: m.id, to: m.to, body: m.body });
});

app.post('/result/:id', (req, res) => {
  const m = queue.find((x) => x.id === req.params.id);
  if (m) { m.status = req.body.status; m.error = req.body.error; }
  console.log('result', req.params.id, req.body.status, req.body.error || '');
  res.json({ ok: true });
});

app.listen(3000, () => console.log('listening on :3000'));
