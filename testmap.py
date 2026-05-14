import numpy as np
import networkx as nx
import matplotlib.pyplot as plt

G = nx.Graph()





G.add_node("309", pos=(34, 7))
G.add_node("307", pos=(38, 7))
G.add_node("306", pos=(38, 10))
G.add_node("305", pos=(38, 12))
G.add_node("304", pos=(38, 15))
G.add_node("303", pos=(38, 18))
G.add_node("302", pos=(38, 20))
G.add_node("301-1", pos=(35, 17))
G.add_node("301-2", pos=(35, 19))
G.add_node("Stair1", pos=(36, 16))
G.add_node("Toilet", pos=(36, 14))
G.add_node("EV1", pos=(35, 10))
G.add_node("EV2", pos=(35, 11))
G.add_node("Stair2", pos=(36, 7))


for i in range(15, 38):
    G.add_node("a_h" + str(i) , pos=(i,8))
    

for i in range(15, 37):
    G.add_edge("a_h"+str(i) ,"a_h"+str(i+1), weight=1)



for i in range(9, 22):
    G.add_node("a_v" + str(i), pos=(37,i))
    

for i in range(9, 21):
    G.add_edge("a_v"+str(i),"a_v"+str(i+1), weight=1)

G.add_edge("a_h37","a_v9", weight=1)



pos = nx.get_node_attributes(G, "pos")

plt.ion()
fig, ax = plt.subplots(figsize=(8, 6))


nx.draw(G, pos, ax=ax, with_labels=True, node_size=100)

plt.show