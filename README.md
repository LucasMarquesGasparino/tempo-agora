# Atemporal

Aplicativo Android em português para consultar o clima atual, previsões por hora
até o fim do dia e previsão diária de 15 dias. Usa a localização do aparelho,
permite pesquisar cidades no mundo todo e guarda uma lista de favoritos.

## Recursos

- Condições atuais com vento, nascer e pôr do sol, UV e horário local do lugar.
- Abas Hoje, Amanhã (hora a hora) e 15 dias, com gráfico e detalhes.
- Unidades escolhidas no menu ⋮: temperatura (°C/°F), vento (km/h / m/s) e chuva (mm / pol).
- Tema claro e escuro, acompanhando o sistema.
- Offline: mostra a última previsão salva, com a idade dos dados, e atualiza
  ao voltar a conexão.
- Backup: exporta e importa favoritos e ajustes em um arquivo JSON escolhido
  por você (útil ao reinstalar o app).
- Widget redimensionável: no tamanho pequeno mostra só o agora; no grande,
  também as horas restantes de hoje e os próximos 7 dias. Toque para abrir o app
  ou em "Atualizar". A cidade do widget escolhe-se no menu ⋮ do app.
- Atualização do widget a cada ~1 h via JobScheduler, só com rede, e atualização
  visual de hora em hora mesmo sem rede.

## Dados

- Previsão e condições atuais: [Open-Meteo](https://open-meteo.com/).
- Busca de cidades: [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api), com dados do GeoNames.
- O plano gratuito do Open-Meteo é para uso não comercial. O app identifica a
  fonte dos dados e o Copernicus na seção “Sobre”.
- As condições atuais são baseadas em dados meteorológicos de modelo atualizados
  em intervalos de 15 minutos; não são uma leitura direta de uma estação.
- O cache da previsão fica só no aparelho (não entra em backups do sistema).

## Compilar

```sh
cd ~/projects/tempo-agora
sh build.sh
```

O `build.sh` roda os testes JVM (`sh tests/run.sh`) antes de empacotar. O APK
assinado é criado em `build/Atemporal.apk` e copiado para
`/sdcard/Documents/APK/Atemporal.apk`.

A chave de assinatura fica em `~/.android-keys/tempo-agora.keystore` (fora do
repositório; criada na primeira compilação se não existir). Use a variável
`ATEMPORAL_KEYSTORE` para apontar para outro arquivo.

A pasta `build/`, os APKs e os keystores ficam fora do Git.
