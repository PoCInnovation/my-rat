package main

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

var (
	clients     = make(map[net.Conn]string)
	clientsMu   sync.Mutex
	messages    []string
	messagesMu  sync.Mutex
	maxMessages = 50
)

func sanitizeMessage(msg string) string {
	var sb strings.Builder
	for _, c := range msg {
		if c < 32 || c == 127 {
			sb.WriteString(fmt.Sprintf("\\x%02X", c))
		} else {
			sb.WriteRune(c)
		}
	}
	return sb.String()
}

func handleClient(conn net.Conn, app *tview.Application, receivedText *tview.TextView) {
	defer func() {
		clientsMu.Lock()
		delete(clients, conn)
		clientsMu.Unlock()
		conn.Close()
	}()

	clientsMu.Lock()
	clients[conn] = conn.RemoteAddr().String()
	clientsMu.Unlock()

	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		message := sanitizeMessage(scanner.Text())

		messagesMu.Lock()
		messages = append([]string{message}, messages...)
		if len(messages) > maxMessages {
			messages = messages[:maxMessages]
		}
		messagesMu.Unlock()

		app.QueueUpdateDraw(func() {
			receivedText.SetText(strings.Join(messages, "\n"))
		})
	}

	if err := scanner.Err(); err != nil {
		log.Println("Error reading from client:", err)
	}
}

func main() {
	PORT := 3000
	listen, err := net.Listen("tcp", ":"+strconv.Itoa(PORT))
	if err != nil {
		log.Fatalf("Error starting server: %v", err)
	}
	defer listen.Close()

	log.Printf("Server listening on port %d", PORT)

	app := tview.NewApplication()

	commandOutput := tview.NewTextView().SetText("Server Output...").SetDynamicColors(true)
	receivedText := tview.NewTextView().SetText("Waiting for messages...").SetDynamicColors(true).SetScrollable(true)
	commandInput := tview.NewInputField().SetLabel("Server > ")

	commandInput.SetDoneFunc(func(key tcell.Key) {
		command := commandInput.GetText()
		commandInput.SetText("")

		go func() {
			switch command {
			case "":
				break

			case "exit":
				app.QueueUpdateDraw(func() {
					commandOutput.SetText("Shutting down server...")
				})
				log.Println("Server shutting down...")
				listen.Close()
				app.Stop()

			case "list":
				clientsMu.Lock()
				clientList := "Connected clients:\n"
				for _, addr := range clients {
					clientList += addr + "\n"
				}
				clientsMu.Unlock()
				app.QueueUpdateDraw(func() {
					commandOutput.SetText(clientList)
				})

			default:
				app.QueueUpdateDraw(func() {
					commandOutput.SetText("Unknown command.")
				})
			}
		}()
	})

	topFlex := tview.NewFlex().
		AddItem(receivedText, 0, 3, false).
		AddItem(commandOutput, 0, 1, false)

	flex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(topFlex, 0, 3, false).
		AddItem(commandInput, 1, 1, true)

	go func() {
		for {
			conn, err := listen.Accept()
			if err != nil {
				if !strings.Contains(err.Error(), "use of closed network connection") {
					log.Println("Error accepting client:", err)
				}
				return
			}
			log.Println("Client connected:", conn.RemoteAddr())
			app.QueueUpdateDraw(func() {
				commandOutput.SetText("Client connected: " + conn.RemoteAddr().String())
			})
			go handleClient(conn, app, receivedText)
		}
	}()

	app.SetRoot(flex, true)
	if err := app.Run(); err != nil {
		log.Fatal(err)
	}

	log.Println("Server stopped. Exiting...")
}
