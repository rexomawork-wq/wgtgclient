import { useState } from 'react'

const FACTS = [
  '67 — это рост 6 футов 7 дюймов (~201 см). Идеальный рост для мема.',
  'Всё началось с трека Skrilla — «Doot Doot (6 7)».',
  'LaMelo Ball (рост 6\'7") стал живым символом мема.',
  '«Сикс-севен» кричат в школах, на стадионах и в TikTok.',
  '67 — универсальный ответ: на вопрос, на счёт, на жизнь.',
  'Если видишь 67 на часах — день будет легендарным.',
  '67 нельзя объяснить. 67 можно только прочувствовать.',
]

const CHANTS = ['СИКС-СЕВЕН!', 'SIX-SEVEN!', 'ШЕСТЬ-СЕМЬ!', '6-7! 6-7! 6-7!', 'DOOT DOOT!']

function shout(text) {
  try {
    const u = new SpeechSynthesisUtterance(text)
    u.lang = 'en-US'
    u.pitch = 0.6
    u.rate = 0.9
    speechSynthesis.cancel()
    speechSynthesis.speak(u)
  } catch {}
}

export default function App() {
  const [count, setCount] = useState(0)
  const [factIdx, setFactIdx] = useState(0)
  const [chant, setChant] = useState('СИКС-СЕВЕН!')
  const [openFaq, setOpenFaq] = useState(0)

  const click67 = () => {
    const n = count + 1
    setCount(n)
    if (n % 10 === 0) setFactIdx(Math.floor(Math.random() * FACTS.length))
    if (n === 67) shout('Six Seven!')
  }

  const randomChant = () => {
    const c = CHANTS[Math.floor(Math.random() * CHANTS.length)]
    setChant(c)
    shout(c)
  }

  const progress = Math.min(100, (count / 67) * 100)

  return (
    <>
      <nav className="nav">
        <div className="nav-inner">
          <a className="logo" href="#top">
            <span className="logo-badge">67</span> СИКС-СЕВЕН
          </a>
          <div className="nav-links">
            <a href="#history">История</a>
            <a href="#meaning">Значение</a>
            <a href="#play">Кликер</a>
            <a href="#use">Применение</a>
            <a href="#faq">FAQ</a>
          </div>
          <button className="btn btn-yellow" onClick={() => shout('Six Seven!')}>Крикнуть 67</button>
        </div>
      </nav>

      <div className="marquee">
        <div className="marquee-inner">
          {'SIX SEVEN • 6-7 • СИКС-СЕВЕН • DOOT DOOT • '.repeat(8)}
          {'SIX SEVEN • 6-7 • СИКС-СЕВЕН • DOOT DOOT • '.repeat(8)}
        </div>
      </div>

      <header className="hero container" id="top">
        <div className="hero-bg">67</div>
        <div className="hero-pill"><span className="dot" /> главный мем 2025–2026 • тикток • школа • стадион</div>
        <h1 className="hero-title">
          <span className="six">6</span>-<span className="seven">7</span>
        </h1>
        <p className="hero-sub">Если ты знаешь — ты знаешь. <span>СИКС-СЕВЕН.</span></p>
        <p className="hero-desc">
          Сайт-легенда про число, которое захватило интернет. Трек Skrilla, рост LaMelo Ball
          и тысячи школьников, орущих «six-seven» без причины. Причины и не надо — это 67.
        </p>
        <div className="hero-cta">
          <a href="#play" className="btn btn-yellow btn-big shake">НАКЛИКАТЬ 67 👆</a>
          <a href="#history" className="btn btn-ghost btn-big">Откуда мем?</a>
        </div>
        <div className="hero-stats">
          <div className="stat"><b>6'7"</b><small>~201 см чистого вайба</small></div>
          <div className="stat"><b>∞</b><small>просмотров в TikTok</small></div>
          <div className="stat"><b>67</b><small>уровень легенды</small></div>
        </div>
      </header>

      <main className="container">
        <section id="history">
          <h2 className="h2">История мема <em>6-7</em></h2>
          <p className="lead">От чикагского дрилла до криков в столовой. Весь лор за 4 шага.</p>
          <div className="timeline">
            <div className="t-item">
              <div className="t-year">ШАГ 1</div>
              <div><h4>🎵 Skrilla — «Doot Doot (6 7)»</h4><p>Трек с назойливым хуком «6-7» завирусился. Бит + повторение = идеальная формула мема. Все начали напевать два числа без контекста.</p></div>
            </div>
            <div className="t-item">
              <div className="t-year">ШАГ 2</div>
              <div><h4>🏀 LaMelo Ball, рост 6'7"</h4><p>Разыгрывающий Charlotte Hornets ростом 6 футов 7 дюймов стал лицом мема. Эдиты с его хайлайтами под «Doot Doot» разлетелись по TikTok и Reels.</p></div>
            </div>
            <div className="t-item">
              <div className="t-year">ШАГ 3</div>
              <div><h4>📱 TikTok-эпидемия</h4><p>Дети орут «SIX-SEVEN!» в коридорах, на уроках, в магазинах. Учителя в бешенстве, интернет в восторге. Словарь мемов официально пополнен.</p></div>
            </div>
            <div className="t-item">
              <div className="t-year">ШАГ 4</div>
              <div><h4>🏟️ Ты здесь</h4><p>Мем вышел за пределы экрана: кричалки на стадионах, принты, этот сайт. 67 — уже не число, а состояние души.</p></div>
            </div>
          </div>
        </section>

        <section id="meaning">
          <h2 className="h2">Что значит <em>67</em>?</h2>
          <p className="lead">Коротко: всё. Длинно — вот:</p>
          <div className="grid grid-3">
            <div className="card hl"><span className="tag">ОСНОВА</span><div className="card-emoji">📏</div><h3>Рост 6'7"</h3><p>201 см. Высокий, дерзкий, заметный. Как и сам мем — невозможно игнорировать.</p></div>
            <div className="card"><span className="tag">ВАЙБ</span><div className="card-emoji">🌀</div><h3>Ответ на всё</h3><p>«Сколько будет?» — «67». «Во сколько?» — «67». «Почему?» — «СИКС-СЕВЕН». Логика не требуется.</p></div>
            <div className="card"><span className="tag">ЗВУК</span><div className="card-emoji">🔊</div><h3>Крик души</h3><p>Оно создано, чтобы орать. Попробуй сказать «six-seven» тихо. Не получится. Проверено.</p></div>
            <div className="card"><span className="tag">СТИЛЬ</span><div className="card-emoji">🏀</div><h3>Баскетбольная аура</h3><p>Стритбол, кроссы, эдиты LaMelo. 67 — это +100 к ауре автоматически.</p></div>
            <div className="card"><span className="tag">МАГИЯ</span><div className="card-emoji">🪄</div><h3>Счастливое число</h3><p>Увидел 67 на номере, часах или чеке — жди хороших новостей. Так гласит легенда.</p></div>
            <div className="card"><span className="tag">ФИЛОСОФИЯ</span><div className="card-emoji">🧠</div><h3>Необъяснимое</h3><p>Пытаться объяснить 67 — как объяснять шутку. Либо ты внутри, либо «ну и что». Ты внутри.</p></div>
          </div>
        </section>

        <section id="play">
          <h2 className="h2">Кликер <em>67</em></h2>
          <p className="lead">Священный ритуал. Накликай ровно 67, чтобы постичь дзен. Осторожно: затягивает.</p>
          <div className="clicker-box">
            <div style={{color:'#a1a1aa', fontWeight:700}}>ТВОЙ СЧЁТ</div>
            <div className={`clicker-num ${count >= 67 ? 'done' : ''}`}>{count}</div>
            {count === 67 && <div style={{fontFamily:'Arial Black', color:'#facc15', fontSize:22}}>🎉 SIX-SEVEN ДОСТИГНУТ! ТЫ ЛЕГЕНДА 🎉</div>}
            {count > 67 && <div style={{color:'#a1a1aa'}}>Перебор! Но уважение. Жми «сброс» и иди к идеалу.</div>}
            <div style={{marginTop:14}}>
              <button className="clicker-btn" onClick={click67}>67</button>
            </div>
            <div className="progress"><i style={{width: `${progress}%`}} /></div>
            <div style={{marginTop:8, fontSize:13, color:'#a1a1aa'}}>{Math.floor(progress)}% до просветления</div>
            <div className="chant-row">
              <button className="btn btn-ghost" onClick={() => { setCount(0) }}>Сброс</button>
              <button className="btn btn-ghost" onClick={() => setFactIdx((factIdx+1)%FACTS.length)}>Другой факт 📜</button>
              <button className="btn btn-yellow" onClick={randomChant}>Чант: {chant} 🔊</button>
            </div>
            <p className="fact">📜 Факт: {FACTS[factIdx]}</p>
          </div>
        </section>

        <section id="use">
          <h2 className="h2">Как использовать <em>67</em></h2>
          <p className="lead">Гайд по выживанию в эпоху сикс-севен.</p>
          <div className="grid grid-2">
            <div className="card sit"><span className="tag">В ШКОЛЕ</span><p>Учитель: «Кто не сделал домашку?» Весь класс: <b>«СИКС-СЕВЕН!»</b> Работает безотказно (нет).</p></div>
            <div className="card sit"><span className="tag">В ПЕРЕПИСКЕ</span><p>На любое сообщение отвечай <b>«67»</b>. «Привет» — «67». «Ты где?» — «67». Диалог станет легендой.</p></div>
            <div className="card sit"><span className="tag">В СПОРТЕ</span><p>Забил, отжался, прибежал первым? Руки вверх и <b>«SIX-SEVEN!»</b>. Аура +67.</p></div>
            <div className="card sit"><span className="tag">В ЖИЗНИ</span><p>Плохое настроение? Встань, вдохни и проори <b>«ШЕСТЬ-СЕМЬ!»</b>. Настроение не улучшится, но будет смешно.</p></div>
          </div>
        </section>

        <section id="faq">
          <h2 className="h2">FAQ про <em>67</em></h2>
          <p className="lead">Вопросы, которые все задают. Ответы, которые никто не ждал.</p>
          <div className="faq">
            {[
              ['Что вообще значит 67?', 'Изначально — рост 6 футов 7 дюймов из трека Skrilla «Doot Doot (6 7)» и мемов про LaMelo Ball. Сейчас — универсальный мем-реакция: кричалка, ответ на всё и знак своих.'],
              ['Почему все орут «сикс-севен»?', 'Потому что это заразно. Хук трека + TikTok-эдиты + школьная культура = идеальный вирусный звук. Орать весело, вот и орут.'],
              ['Это связано с баскетболом?', 'Да. LaMelo Ball ростом 6\'7" — главное лицо мема. Но знать баскетбол не обязательно: достаточно уметь орать.'],
              ['67 — это ещё актуально?', 'Мем пикoвал в 2025 и жив в 2026. Даже если волна спадёт, 67 останется классикой вроде «OK» и «лол».'],
              ['Как правильно кричать?', 'Громко, с растяжкой: «СИИИКС... СЕВЕН!». Руки можно развести в стороны. Бонус — если в коридоре с эхом.'],
            ].map(([q, a], i) => (
              <div className="faq-item" key={i}>
                <button className="faq-q" onClick={() => setOpenFaq(openFaq === i ? -1 : i)}>
                  {q} <span>{openFaq === i ? '−' : '+'}</span>
                </button>
                {openFaq === i && <div className="faq-a">{a}</div>}
              </div>
            ))}
          </div>
        </section>
      </main>

      <footer>
        <div className="container footer-inner">
          <div><b style={{color:'#fff'}}>67 — СИКС-СЕВЕН</b> • сделано фанатом мема • React + Vite</div>
          <div>Skrilla • LaMelo Ball • TikTok • 2026 • <span style={{color:'#facc15'}}>6-7 навсегда</span></div>
        </div>
      </footer>
    </>
  )
}
