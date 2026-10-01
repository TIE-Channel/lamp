package home.lampremote

/** The eight buttons of the lamp's radio remote, in the order they sit on it. */
enum class Cmd(val label: String, val icon: Int) {
  CCT("Оттенок", R.drawable.ic_cct),
  TIMER("Таймер 60 с", R.drawable.ic_timer),
  COLD("Холодный", R.drawable.ic_cold),
  WARM("Тёплый", R.drawable.ic_warm),
  POWER("Вкл / выкл", R.drawable.ic_power),
  NIGHT("Ночник", R.drawable.ic_night),
  BR_UP("Ярче", R.drawable.ic_br_up),
  BR_DOWN("Темнее", R.drawable.ic_br_down),
}

/** The lamp's state as the widget sees it. */
enum class Mode { OFF, DIM, BRIGHT, ON }
