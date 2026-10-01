# Tempo Agora

Aplicativo Android em português para consultar o clima atual, previsões por hora
até o fim do dia e previsão diária de 15 dias. Usa a localização do aparelho,
permite pesquisar cidades no mundo todo e guarda uma lista de favoritos.

## Dados

- Previsão e condições atuais: [Open-Meteo](https://open-meteo.com/).
- Busca de cidades: [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api), com dados do GeoNames.
- O plano gratuito do Open-Meteo é para uso não comercial. O app identifica a
  fonte dos dados e o Copernicus na seção “Sobre”.
- As condições atuais são baseadas em dados meteorológicos de modelo atualizados
  em intervalos de 15 minutos; não são uma leitura direta de uma estação.

## Compilar

```sh
cd ~/projects/tempo-agora
bash build.sh
```

O APK assinado localmente será criado em `build/Tempo-Agora.apk`. A pasta `build/`
e o keystore local ficam fora do Git.
