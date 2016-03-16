

import sys
import copy
# import plotly.offline as py
# import plotly.graph_objs as go


class EdgeFile(object):
    """
    This class is a Python object representation of an edge file. The expected layout of the edge file is as follows:
    each line consists of three elements: two strings and a floating point number, separated by spaces
    """
    def __init__(self, filename, objScoreKey):

        # the file that the edge data is loaded from
        self.filename = filename

        # the 2-D, nominal parameter space
        self.parSpace = []

        # the objective score associated with a point in the parameter space
        self.objScores = []

        # create an empty set
        self.ulistphotos = set()

        # commence the loading from file
        if objScoreKey == 'goldberg':
            raise Exception('The key \'goldberg\' is reserved for calculating the Pareto scores later on.')
        else:
            self.load(objScoreKey)

    def load(self, objScoreKey):

        # read all the data from the edge file
        with open(self.filename, 'r') as f:
            rdata = f.read()

        lines = rdata.splitlines(False)
        for line in lines:
            photo1, photo2, objScore = line.split(' ')
            self.parSpace.append({'x': photo1, 'y': photo2})
            self.objScores.append({objScoreKey: objScore})

            if photo1 not in self.ulistphotos:
                self.ulistphotos.add(photo1)

            if photo2 not in self.ulistphotos:
                self.ulistphotos.add(photo2)



    # def plotlytest(self):
    #
    #     data = [
    #         go.Heatmap(
    #             z=[[1, 20, 30, 50, 1], [20, 1, 60, 80, 30], [30, 60, 1, -10, 20]],
    #             x=['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'],
    #             y=['Morning', 'Afternoon', 'Evening']
    #         )
    #     ]
    #     plot_url = py.plot(data, filename='labelled-heatmap')
    #
    # def show(self):
    #
    #     labels = sorted(self.ulistphotos)



if __name__ == '__main__':


    obj1 = EdgeFile('../data/pentax/edgelist-pentax-obj1.txt', 'obj1')
    obj2 = EdgeFile('../data/pentax/edgelist-pentax-obj2.txt', 'obj2')

    print('Done.')