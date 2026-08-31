#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

echo
echo "========================================"
echo " Echo360 Companion v0.4.0-rc1"
echo "========================================"
echo

pkg update -y
pkg install nodejs -y

if [ ! -f config.json ]; then
  if [ -f "$HOME/Echo360_Companion_v0.2/config.json" ]; then
    cp "$HOME/Echo360_Companion_v0.2/config.json" config.json
    echo "Config v0.2 importada."
  else
    cp config.example.json config.json
    echo "Config padrao criada."
  fi
fi

npm install

chmod +x start.sh stop.sh

echo
echo "Instalacao concluida."
echo "Rode: ./start.sh"
