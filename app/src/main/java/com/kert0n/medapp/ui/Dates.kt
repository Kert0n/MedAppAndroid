package com.kert0n.medapp.ui

import java.time.format.DateTimeFormatter

/**
 * Как приложение пишет день: `31.03.2027`. Один формат на все экраны — пять его копий по файлам
 * разъехались бы порознь, и человек читал бы дату по-разному на соседних экранах.
 */
internal val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")
