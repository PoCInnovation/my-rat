package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

const (
	TEXT_MESSAGE = 1
	FILE_START   = 2
	FILE_DATA    = 3
	FILE_END     = 4
)

var (
	clients     = make(map[net.Conn]string)
	clientsMu   sync.Mutex
	messages    []string
	messagesMu  sync.Mutex
	maxMessages = 50
	uploadDir   = "uploads"
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

func handleClient(conn net.Conn, app *tview.Application, receivedText *tview.TextView, commandOutput *tview.TextView) {
	defer func() {
		clientsMu.Lock()
		delete(clients, conn)
		clientsMu.Unlock()
		conn.Close()
	}()

	clientsMu.Lock()
	clients[conn] = conn.RemoteAddr().String()
	clientsMu.Unlock()

	if err := os.MkdirAll(uploadDir, 0755); err != nil {
		log.Printf("Failed to create upload directory: %v", err)
		return
	}

	headerBuf := make([]byte, 5)
	var currentFile *os.File
	var currentFilename string
	var fileSize int32
	var bytesReceived int32

	for {
		_, err := io.ReadFull(conn, headerBuf)
		if err != nil {
			if err != io.EOF {
				log.Printf("Error reading header: %v", err)
			}
			break
		}

		messageType := headerBuf[0]
		messageLength := binary.BigEndian.Uint32(headerBuf[1:5])

		switch messageType {
		case TEXT_MESSAGE:
			msgBuf := make([]byte, messageLength)
			_, err := io.ReadFull(conn, msgBuf)
			if err != nil {
				log.Printf("Error reading message: %v", err)
				return
			}

			message := sanitizeMessage(string(msgBuf))

			messagesMu.Lock()
			messages = append([]string{message}, messages...)
			if len(messages) > maxMessages {
				messages = messages[:maxMessages]
			}
			messagesMu.Unlock()

			app.QueueUpdateDraw(func() {
				receivedText.SetText(strings.Join(messages, "\n"))
			})

		case FILE_START:
			fileInfoBuf := make([]byte, messageLength)
			_, err := io.ReadFull(conn, fileInfoBuf)
			if err != nil {
				log.Printf("Error reading file info: %v", err)
				return
			}

			fileInfo := string(fileInfoBuf)
			parts := strings.Split(fileInfo, "|")
			if len(parts) != 2 {
				log.Printf("Invalid file info format: %s", fileInfo)
				continue
			}

			currentFilename = parts[0]
            fileSize, err := strconv.ParseInt(parts[1], 10, 32)
			if err != nil {
				log.Printf("Invalid file size: %v", err)
				continue
			}

			safeFilename := filepath.Base(currentFilename)
			fullPath := filepath.Join(uploadDir, safeFilename)
			
			if _, err := os.Stat(fullPath); err == nil {
				ext := filepath.Ext(safeFilename)
				name := strings.TrimSuffix(safeFilename, ext)
				timestamp := time.Now().Format("20060102_150405")
				safeFilename = fmt.Sprintf("%s_%s%s", name, timestamp, ext)
				fullPath = filepath.Join(uploadDir, safeFilename)
			}

			currentFile, err = os.Create(fullPath)
			if err != nil {
				log.Printf("Error creating file: %v", err)
				continue
			}

			bytesReceived = 0
			
			status := fmt.Sprintf("Starting file transfer: %s (%d bytes)", safeFilename, fileSize)
			log.Println(status)
			app.QueueUpdateDraw(func() {
				commandOutput.SetText(status)
			})

		case FILE_DATA:
			if currentFile == nil {
				log.Println("Received file data without file_start")
				continue
			}

			dataBuf := make([]byte, messageLength)
			_, err := io.ReadFull(conn, dataBuf)
			if err != nil {
				log.Printf("Error reading file data: %v", err)
                currentFile.Close()
				return
			}

			_, err = currentFile.Write(dataBuf)
			if err != nil {
				log.Printf("Error writing to file: %v", err)
				currentFile.Close()
				currentFile = nil
				continue
			}

            fileSize += 1
			bytesReceived += int32(messageLength)
            quotient := float64(fileSize) * 100.0 + 1.0
			progress := float64(bytesReceived) / quotient
			
            mod := fileSize / 10
            if mod == 0 {
                mod = 1
            }
			if bytesReceived % mod == 0 ||
                bytesReceived == fileSize {
				status := fmt.Sprintf("Receiving: %s (%.1f%%)", currentFilename, progress)
				app.QueueUpdateDraw(func() {
					commandOutput.SetText(status)
				})
			}

		case FILE_END:
			if currentFile != nil {
				currentFile.Close()
				status := fmt.Sprintf("File received: %s (%d bytes)", currentFilename, bytesReceived)
				log.Println(status)
				app.QueueUpdateDraw(func() {
					commandOutput.SetText(status)
				})
				currentFile = nil
			}
		}
	}

    log.Println("Client disconnected:", conn.RemoteAddr())
}

func sendFile(conn net.Conn, filePath string, app *tview.Application, commandOutput *tview.TextView) {
	file, err := os.Open(filePath)
	if err != nil {
		log.Printf("Error opening file: %v", err)
		app.QueueUpdateDraw(func() {
			commandOutput.SetText(fmt.Sprintf("Error: Could not open file %s", filePath))
		})
		return
	}
	defer file.Close()

	fileInfo, err := file.Stat()
	if err != nil {
		log.Printf("Error getting file info: %v", err)
		return
	}

	fileInfoStr := fmt.Sprintf("%s|%d", filepath.Base(filePath), fileInfo.Size())
	
	headerBuf := make([]byte, 5)
	headerBuf[0] = FILE_START
	binary.BigEndian.PutUint32(headerBuf[1:], uint32(len(fileInfoStr)))
	
	_, err = conn.Write(headerBuf)
	if err != nil {
		log.Printf("Error sending file start header: %v", err)
		return
	}
	
	_, err = conn.Write([]byte(fileInfoStr))
	if err != nil {
		log.Printf("Error sending file info: %v", err)
		return
	}

	buffer := make([]byte, 4096)
	bytesSent := int64(0)
	
	for {
		n, err := file.Read(buffer)
		if err != nil {
			if err != io.EOF {
				log.Printf("Error reading from file: %v", err)
			}
			break
		}
		
		headerBuf[0] = FILE_DATA
		binary.BigEndian.PutUint32(headerBuf[1:], uint32(n))
		
		_, err = conn.Write(headerBuf)
		if err != nil {
			log.Printf("Error sending data header: %v", err)
			return
		}
		
		_, err = conn.Write(buffer[:n])
		if err != nil {
			log.Printf("Error sending file data: %v", err)
			return
		}
		
		bytesSent += int64(n)
		progress := float64(bytesSent) / float64(fileInfo.Size()) * 100.0
		
		if bytesSent % (fileInfo.Size() / 10) == 0 || bytesSent == fileInfo.Size() {
			status := fmt.Sprintf("Sending: %s (%.1f%%)", filepath.Base(filePath), progress)
			app.QueueUpdateDraw(func() {
				commandOutput.SetText(status)
			})
		}
	}

	headerBuf[0] = FILE_END
	binary.BigEndian.PutUint32(headerBuf[1:], 0)
	
	_, err = conn.Write(headerBuf)
	if err != nil {
		log.Printf("Error sending file end header: %v", err)
		return
	}

	status := fmt.Sprintf("File sent: %s (%d bytes)", filepath.Base(filePath), bytesSent)
	log.Println(status)
	app.QueueUpdateDraw(func() {
		commandOutput.SetText(status)
	})
}

func getActiveClient() net.Conn {
	clientsMu.Lock()
	defer clientsMu.Unlock()
	
	for conn := range clients {
		return conn
	}
	return nil
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
			parts := strings.SplitN(command, " ", 2)
			cmdName := parts[0]

			switch cmdName {
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
				
			case "send":
				if len(parts) < 2 {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText("Usage: send <message>")
					})
					break
				}
				
				client := getActiveClient()
				if client == nil {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText("No clients connected")
					})
					break
				}
				
                message := parts[1] + "\n"
				headerBuf := make([]byte, 5)
				headerBuf[0] = TEXT_MESSAGE
				binary.BigEndian.PutUint32(headerBuf[1:], uint32(len(message)))
				
				_, err := client.Write(headerBuf)
				if err != nil {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText(fmt.Sprintf("Error sending message: %v", err))
					})
					break
				}
				
				_, err = client.Write([]byte(message))
				if err != nil {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText(fmt.Sprintf("Error sending message: %v", err))
					})
					break
				}
				
				app.QueueUpdateDraw(func() {
					commandOutput.SetText("Message sent")
				})
				
			case "sendfile":
				if len(parts) < 2 {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText("Usage: sendfile <filepath>")
					})
					break
				}
				
				client := getActiveClient()
				if client == nil {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText("No clients connected")
					})
					break
				}
				
				filePath := parts[1]
				go sendFile(client, filePath, app, commandOutput)
				
			case "listfiles":
				files, err := os.ReadDir(uploadDir)
				if err != nil {
					app.QueueUpdateDraw(func() {
						commandOutput.SetText(fmt.Sprintf("Error reading upload directory: %v", err))
					})
					break
				}
				
				fileList := fmt.Sprintf("Files in %s:\n", uploadDir)
				if len(files) == 0 {
					fileList += "No files"
				} else {
					for _, file := range files {
						info, _ := file.Info()
						size := "unknown"
						if info != nil {
							size = strconv.FormatInt(info.Size(), 10) + " bytes"
						}
						fileList += fmt.Sprintf("- %s (%s)\n", file.Name(), size)
					}
				}
				
				app.QueueUpdateDraw(func() {
					commandOutput.SetText(fileList)
				})
				
			default:
				app.QueueUpdateDraw(func() {
					commandOutput.SetText("Unknown command. Available commands:\n" +
						"- send <message>\n" +
						"- sendfile <filepath>\n" +
						"- listfiles\n" +
						"- list\n" +
						"- exit")
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
			go handleClient(conn, app, receivedText, commandOutput)
		}
	}()

	app.SetRoot(flex, true)
	if err := app.Run(); err != nil {
		log.Fatal(err)
	}

	log.Println("Server stopped. Exiting...")
}
