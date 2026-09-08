# EchoCore Resident R2 — correção de rede e diagnóstico

## Problema confirmado no código

Base examinada: `echocore/remote-loader-v1`, commit
`845c7a129f3cc5d734c3d9e0d91288706881c028` (EchoCore XEX CI #50).

O Resident chamava `NetDll_XNetStartup(SYSAPP, NULL)` e não configurava
`SO_INSECURE` (`0x5801`) nem a opção complementar `0x5802`. O bootstrap de
hardware já usava o ABI `echo_xnet_startup_params` e essas opções. A correção
do bootstrap não havia sido propagada para o servidor residente.

Os testes antigos ignoravam os parâmetros de startup e de `setsockopt`,
portanto aprovavam esse caminho. Ao validar os argumentos, o teste sobre o
código anterior falhou em `startup != NULL`. Isso comprova a divergência de
implementação; a causa completa da tela preta no console ainda exige teste
físico com este binário.

## Alterações

- Usa o ABI de 13 bytes e a configuração LAN compartilhada com o bootstrap,
  mantendo `XNCALLER_SYSAPP = 2` em todo o transporte.
- Exige `0x5801` no socket de escuta e nos sockets aceitos; trata `0x5802`
  como opcional, conforme o bootstrap. A autenticação EchoLink permanece ativa.
- Verifica a solicitação de parada entre leituras/escritas parciais. Antes,
  um cliente enviando bytes lentamente podia renovar a espera a cada chamada
  e prolongar o join do worker durante o descarregamento.
- Diferencia falhas de XNet, WSA, socket, LAN, bind e listen. Libera apenas os
  recursos que chegaram a ser inicializados.
- O worker mostra uma notificação após a porta estar configurada para escuta,
  ou uma falha de pareamento/rede. As mensagens não contêm token nem segredo.
- O Loader distingue módulo carregado de serviço funcional. A prova de conexão
  continua sendo PING/PONG e autenticação no aplicativo.

Não há alteração do protocolo, token, `pairing.dat`, App Android, NAND ou
`launch.ini`. Esta rodada não aprova instalação automática nem hot unload.

## Testes

`bash echocore/tests/run-host-tests.sh` executa a suíte de host. A CI usa
Clang com `-Wall -Wextra -Werror -pedantic` e compila os XEX com o OpenXeChain
fixado no workflow. O teste local com GCC permite apenas o aviso já existente
de conversão do ponteiro de função no ABI `ExCreateThread`; a validação Clang
estrita continua na CI, sem relaxamento de flags no repositório.

Os testes novos cobrem os parâmetros XNet, ordem da configuração LAN antes de
bind, opções dos clientes aceitos, `0x5802` não suportado, sete falhas de
inicialização com limpeza correta, notificação de prontidão e interrupção de
recepção/envio fragmentados. A suíte existente cobre pareamento, autenticação,
framing, filesystem e ciclo de vida do Resident.

## Validação no Xbox do Jhony

1. Preserve `Hdd1:\Echo360\EchoCore\pairing.dat` e seu `launch.ini` de SOS.
   Não execute o Resident diretamente e não abra o antigo `EchoCore.xex`.
2. Substitua `EchoCoreResident.xex` e `EchoCoreLoader.xex` em
   `Hdd1:\Echo360\EchoCore\` pelos arquivos **da mesma compilação R2**.
   Se o Resident antigo já estiver carregado, um reinício normal será necessário
   para usar os novos bytes; abrir o Loader outra vez não substitui o módulo
   que está em memória. Não reinicie durante uma transferência.
3. Na Aurora, execute somente `EchoCoreLoader.xex`. Anote a última mensagem.
   A notificação `EchoCore R2: ouvindo na porta 36000` comprova a inicialização
   local do listener, não a autenticação do celular.
4. No Termux, com o Xbox ligado na mesma rede, execute:

   ```sh
   nc -vz -w 3 192.168.1.18 36000
   ```

5. Abra o Echo360 App com o token já cadastrado e teste a conexão. Para uma
   prova independente do protocolo, use o script completo incluído no pacote:

   ```sh
   python echolink_ping.py 192.168.1.18 36000
   ```

6. Confirme retorno à Aurora, repita a conexão e depois teste Aurora → jogo →
   Aurora. A aprovação de residência depende de o PING/PONG e a autenticação
   continuarem funcionando após essas transições.

Se houver tela preta, registre a última notificação e o resultado do teste de
rede. CI verde e a presença do caminho nos plugins do DashLaunch não comprovam
que o worker está vivo. Não apague o pareamento para tentar corrigir rede.

## Referências técnicas

- Contrato e configuração já existentes em
  `echocore/openxechain/echo_xnet_abi.h` e `echo_core.c`.
- [Xenia: estrutura de startup e chamadas NetDll](https://github.com/xenia-project/xenia/blob/master/src/xenia/kernel/xam/xam_net.cc).
- [Xenia: tratamento de 0x5801/0x5802](https://github.com/xenia-project/xenia/blob/master/src/xenia/kernel/xsocket.cc).
- [xecorelib: ABI de threads do kernel](https://github.com/OpenXeChain/xecorelib/blob/c65d67e071357acade681f04c46ae9719797f239/xboxkrnl/xboxkrnl_threads.h).

Essas referências ajudam a auditar a implementação; emulação não substitui o
teste físico da placa Corona/RGH.
